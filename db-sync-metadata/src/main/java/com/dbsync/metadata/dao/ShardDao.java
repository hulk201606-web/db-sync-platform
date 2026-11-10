package com.dbsync.metadata.dao;

import com.dbsync.common.enums.ClaimStrategy;
import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.enums.ShardState;
import com.dbsync.common.model.Shard;
import com.dbsync.common.model.ShardRowSums;
import com.dbsync.common.model.ShardSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 分片 DAO —— 平台并发正确性的最核心一环（架构 §4.2）。
 *
 * <p><b>不可违反的约束</b>：
 * <ol>
 *   <li>{@link #claimOne} 的候选判据必须包含"租约已过期"，即
 *       {@code state='PENDING' OR (state IN ('CLAIMED','RUNNING') AND lease_expire_at < now)}；
 *       缺失后半句会导致宕机实例的分片永远无人接管。</li>
 *   <li>{@link #takeOverExpired} 的判据必须与 {@link #claimOne} <b>完全一致</b>
 *       （同一表达式、同一时间来源 now），否则两条路径会互相"看不见"对方可回收的分片。</li>
 *   <li>抢占必须在<b>同一元数据库事务</b>内完成 "SELECT ... FOR UPDATE" + "UPDATE ... WHERE version=?"，
 *       否则两个实例可能读到同一行。</li>
 * </ol>
 *
 * <p>三种抢占策略见架构 §4.2.3；{@code SKIP_LOCKED}/{@code NOWAIT_PROBE} 在不支持的库上
 * 会<b>运行时自动降级</b>到 {@code OPTIMISTIC_CAS}（永久降级并打 WARN），
 * 保证"任何元数据库都能跑"，不需要部署时人工试错。
 */
@Repository
public class ShardDao extends AbstractMetaDao {

    private static final Logger log = LoggerFactory.getLogger(ShardDao.class);

    /** NOWAIT 探测的最大候选数（架构 §4.2.3：最多探测 32 条）。 */
    private static final int PROBE_CANDIDATE_LIMIT = 32;

    private final TransactionTemplate tx;

    /** 配置的策略（可能因运行时降级而变化，故非 final）。 */
    private volatile ClaimStrategy strategy;

    private final RowMapper<Shard> mapper = new RowMapper<Shard>() {
        @Override
        public Shard mapRow(ResultSet rs, int rowNum) throws SQLException {
            Shard shard = new Shard(rs.getLong("id"), rs.getLong("task_id"),
                    rs.getLong("table_mapping_id"), rs.getInt("shard_no"),
                    RangeKind.valueOf(rs.getString("range_kind")), rs.getString("pk_start"),
                    rs.getString("pk_end"), ShardState.valueOf(rs.getString("state")),
                    rs.getString("lease_owner"), rs.getString("last_pk"),
                    rs.getLong("committed_rows"), rs.getInt("attempt"), rs.getInt("version"));
            return shard;
        }
    };

    /**
     * 只读快照映射器（MIG-06 分片清单）。
     *
     * <p>与 {@link #mapper} 刻意分开：执行期对象不需要 {@code last_error/started_at}
     * 这类"诊断列"，看板需要；两张映射器各自只读自己关心的列。
     */
    private final RowMapper<ShardSnapshot> snapshotMapper = new RowMapper<ShardSnapshot>() {
        @Override
        public ShardSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ShardSnapshot(rs.getLong("id"), rs.getLong("task_id"),
                    rs.getLong("table_mapping_id"), rs.getInt("shard_no"),
                    RangeKind.valueOf(rs.getString("range_kind")), rs.getString("pk_start"),
                    rs.getString("pk_end"), ShardState.valueOf(rs.getString("state")),
                    rs.getString("lease_owner"), instantOf(rs, "lease_expire_at"),
                    rs.getString("last_pk"), rs.getLong("committed_rows"),
                    rs.getLong("source_rows"), rs.getInt("attempt"),
                    rs.getString("last_error"), instantOf(rs, "started_at"),
                    instantOf(rs, "finished_at"));
        }
    };

    /**
     * 构造。
     *
     * <p><b>抢占策略只认 {@code dbsync.claim-strategy} 这一个真源</b>
     * （与 {@code CoreConfig} 选策略 bean 用的是同一个键）。原来绑的
     * {@code db-sync.meta.claim-strategy} 是重复键：两处独立绑定一旦被分别覆盖就会漂移；
     * 这里改为读同一键，不再引入第二个真源。
     * {@code chosen = forced == null ? strategy : forced} 的降级语义保持不变。
     *
     * @param jdbc     元数据库 JdbcTemplate
     * @param tx       元数据库事务模板（必须 REQUIRES_NEW）
     * @param strategy 配置的抢占策略
     */
    public ShardDao(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc,
                    @Qualifier("metaTransactionTemplate") TransactionTemplate tx,
                    @Value("${dbsync.claim-strategy:SKIP_LOCKED}") ClaimStrategy strategy) {
        super(jdbc);
        this.tx = tx;
        this.strategy = strategy == null ? ClaimStrategy.SKIP_LOCKED : strategy;
    }

    /**
     * 当前生效的抢占策略（可能在降级后与配置值不同，供监控/日志使用）。
     *
     * @return 生效策略
     */
    public ClaimStrategy effectiveStrategy() {
        return strategy;
    }

    /**
     * 批量写入分片（规划阶段一次落库）。
     *
     * @param shards 分片列表
     * @return 影响行数
     */
    public int insertBatch(List<Shard> shards) {
        if (shards == null || shards.isEmpty()) {
            return 0;
        }
        Instant now = now();
        List<Object[]> args = new ArrayList<Object[]>(shards.size());
        for (Shard s : shards) {
            args.add(new Object[] {s.getId(), s.getTaskId(), s.getTableMappingId(), s.getShardNo(),
                    s.getRangeKind().name(), s.getPkStart(), s.getPkEnd(), s.getState().name(),
                    s.getAttempt(), toTimestamp(now), toTimestamp(now)});
        }
        int[] rows = jdbc.batchUpdate("INSERT INTO sync_shard (id,task_id,table_mapping_id,"
                + "shard_no,range_kind,pk_start,pk_end,state,committed_rows,source_rows,attempt,"
                + "version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,0,-1,?,0,?,?)", args);
        int sum = 0;
        for (int r : rows) {
            sum += r;
        }
        return sum;
    }

    /**
     * 按 ID 查询。
     *
     * @param id 分片 ID
     * @return 分片
     */
    public Optional<Shard> findById(long id) {
        List<Shard> list = jdbc.query("SELECT * FROM sync_shard WHERE id=?", mapper, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 按任务列出全部分片（按 shard_no 升序，保证调度顺序稳定）。
     *
     * @param taskId 任务 ID
     * @return 分片列表
     */
    public List<Shard> findByTask(long taskId) {
        return jdbc.query("SELECT * FROM sync_shard WHERE task_id=? ORDER BY shard_no",
                mapper, taskId);
    }

    /**
     * 按任务 + 状态列出分片。
     *
     * @param taskId 任务 ID
     * @param state  状态
     * @return 分片列表
     */
    public List<Shard> findByTaskAndState(long taskId, ShardState state) {
        return jdbc.query("SELECT * FROM sync_shard WHERE task_id=? AND state=? ORDER BY shard_no",
                mapper, taskId, state.name());
    }

    /**
     * 按 (taskId, shardNo) 查分片。
     *
     * <p>断点表 {@code sync_checkpoint} 的 SHARD scope 存的是 {@code shard_no}（int），
     * 而不是分片主键（雪花 ID，64 位）——{@code CheckpointStore} 靠本方法把两者对应起来。
     *
     * @param taskId  任务 ID
     * @param shardNo 分片序号
     * @return 分片
     */
    public Optional<Shard> findByTaskAndShardNo(long taskId, int shardNo) {
        List<Shard> list = jdbc.query("SELECT * FROM sync_shard WHERE task_id=? AND shard_no=?",
                mapper, taskId, shardNo);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 按表映射列出分片（比对/报表用）。
     *
     * @param tableMappingId 表映射 ID
     * @return 分片列表
     */
    public List<Shard> findByTableMapping(long tableMappingId) {
        return jdbc.query("SELECT * FROM sync_shard WHERE table_mapping_id=? ORDER BY shard_no",
                mapper, tableMappingId);
    }

    /**
     * 分页列出分片快照（MIG-06 分片级进度，前端热力图的数据源）。
     *
     * <p>{@code // ADDED: 04 文档只有"任务级进度"，MIG-06 要求的"分片级"没有任何查询入口；
     * 直接复用 {@link #findByTask(long)} 会在大任务（400+ 分片）上一次拉全表，
     * 因此单独提供带 LIMIT 的分页方法。}
     *
     * <p>LIMIT/OFFSET 只用整型字面量拼接（已在入参处归一化为非负 int），
     * 不存在注入面；元数据库固定为 MySQL 兼容方言，故不引入分页方言抽象。
     *
     * @param taskId 任务 ID
     * @param state  状态过滤（null 表示全部）
     * @param offset 偏移（≥0）
     * @param limit  每页条数（≥1）
     * @return 分片快照列表（按 shard_no 升序，保证分页稳定）
     */
    public List<ShardSnapshot> findPageByTask(long taskId, ShardState state, int offset, int limit) {
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, limit);
        String paging = " ORDER BY shard_no LIMIT " + safeLimit + " OFFSET " + safeOffset;
        if (state == null) {
            return jdbc.query("SELECT * FROM sync_shard WHERE task_id=?" + paging,
                    snapshotMapper, taskId);
        }
        return jdbc.query("SELECT * FROM sync_shard WHERE task_id=? AND state=?" + paging,
                snapshotMapper, taskId, state.name());
    }

    /**
     * 统计分片数（分页总数用）。
     *
     * @param taskId 任务 ID
     * @param state  状态过滤（null 表示全部）
     * @return 条数
     */
    public long countByTaskAndState(long taskId, ShardState state) {
        Long n = state == null
                ? jdbc.queryForObject("SELECT COUNT(1) FROM sync_shard WHERE task_id=?",
                        Long.class, taskId)
                : jdbc.queryForObject("SELECT COUNT(1) FROM sync_shard WHERE task_id=? AND state=?",
                        Long.class, taskId, state.name());
        return n == null ? 0L : n.longValue();
    }

    /**
     * 按表映射分组统计各状态分片数（MIG-06 表级进度）。
     *
     * <p>聚合在库里做（{@code GROUP BY table_mapping_id, state}）：
     * 大任务分片数随数据量增长，把分片全查回 Java 再循环是坏习惯，
     * 也会让"按表进度"这个高频看板接口随分片数线性变慢。
     *
     * @param taskId 任务 ID
     * @return 表映射 ID → (状态 → 数量)；每个表映射都补齐全部枚举值（缺省 0）
     */
    public Map<Long, Map<ShardState, Integer>> countByStateGroupByMapping(long taskId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT table_mapping_id, state, COUNT(1) AS cnt FROM sync_shard "
                        + "WHERE task_id=? GROUP BY table_mapping_id, state", taskId);
        Map<Long, Map<ShardState, Integer>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object mappingId = row.get("table_mapping_id");
            Object stateName = row.get("state");
            Object cnt = row.get("cnt");
            if (mappingId == null || stateName == null || cnt == null) {
                continue;
            }
            ShardState state = parseState(String.valueOf(stateName));
            if (state == null) {
                continue;
            }
            Long key = Long.valueOf(((Number) mappingId).longValue());
            result.computeIfAbsent(key, id -> emptyStateCounts())
                    .put(state, Integer.valueOf(((Number) cnt).intValue()));
        }
        return result;
    }

    /**
     * 按表映射分组汇总行数（MIG-06 表级进度）。
     *
     * <p><b>源侧行数只累加正数</b>：{@code source_rows} 的列默认值是 -1（未知），
     * 直接把 -1 求和会让"3 个分片已知、1 个未跑"的表算出 {@code 100-1=99} 这种无意义的分母，
     * 进而让百分比失真。未知分片按 0 累加，聚合结果 {@code ≤0} 就表示"该表一个分片都没采到源行数"，
     * 由调用方（{@code ShardProgressService}）退回到 {@code row_count_estimate} 估算值。
     *
     * @param taskId 任务 ID
     * @return 表映射 ID → 行数汇总（已提交行数 + 已知源侧行数）
     */
    public Map<Long, ShardRowSums> sumRowsGroupByMapping(long taskId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT table_mapping_id, COALESCE(SUM(committed_rows),0) AS committed, "
                        + "COALESCE(SUM(CASE WHEN source_rows > 0 THEN source_rows ELSE 0 END),0) "
                        + "AS source FROM sync_shard WHERE task_id=? GROUP BY table_mapping_id",
                taskId);
        Map<Long, ShardRowSums> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object mappingId = row.get("table_mapping_id");
            if (mappingId == null) {
                continue;
            }
            Object committed = row.get("committed");
            Object source = row.get("source");
            result.put(Long.valueOf(((Number) mappingId).longValue()),
                    new ShardRowSums(committed == null ? 0L : ((Number) committed).longValue(),
                            source == null ? 0L : ((Number) source).longValue()));
        }
        return result;
    }

    /**
     * 全枚举值置 0 的状态计数表。
     *
     * @return 可变的计数表
     */
    private static Map<ShardState, Integer> emptyStateCounts() {
        Map<ShardState, Integer> counts = new EnumMap<ShardState, Integer>(ShardState.class);
        for (ShardState state : ShardState.values()) {
            counts.put(state, Integer.valueOf(0));
        }
        return counts;
    }

    /**
     * 解析分片状态（未知值记警告并忽略，不因一条脏数据让整个看板 500）。
     *
     * @param name 状态名
     * @return 状态；未知返回 null
     */
    private static ShardState parseState(String name) {
        try {
            return ShardState.valueOf(name);
        } catch (IllegalArgumentException e) {
            log.warn("未知分片状态，忽略：{}", name);
            return null;
        }
    }

    /**
     * 抢占一个分片（架构 §4.2.2 / §4.2.3）。
     *
     * <p>副作用：{@code state=CLAIMED, lease_owner=owner, lease_expire_at=now+lease,
     * heartbeat_at=now, attempt=attempt+1, version=version+1}。
     *
     * @param taskId 任务 ID
     * @param owner  持有者（instanceId:host:port#uuid）
     * @param lease  租约时长（必须 ≤ 30s，见架构 §4.2.5）
     * @return 抢到的分片；无可用分片返回 empty
     */
    public Optional<Shard> claimOne(long taskId, String owner, Duration lease) {
        if (owner == null || owner.isEmpty()) {
            throw new IllegalArgumentException("owner 不能为空");
        }
        Duration effectiveLease = lease == null || lease.isNegative() || lease.isZero()
                ? Duration.ofSeconds(30) : lease;
        switch (strategy) {
            case SKIP_LOCKED:
                try {
                    return claimSkipLocked(taskId, owner, effectiveLease);
                } catch (DataAccessException e) {
                    degrade("SKIP LOCKED", e);
                    return claimOptimisticCas(taskId, owner, effectiveLease);
                }
            case NOWAIT_PROBE:
                try {
                    return claimNoWaitProbe(taskId, owner, effectiveLease);
                } catch (DataAccessException e) {
                    degrade("FOR UPDATE NOWAIT", e);
                    return claimOptimisticCas(taskId, owner, effectiveLease);
                }
            case OPTIMISTIC_CAS:
            default:
                return claimOptimisticCas(taskId, owner, effectiveLease);
        }
    }

    /**
     * 按<b>指定</b>策略抢占（不改全局配置；供 {@code ShardClaimStrategy} 三种实现显式调用）。
     *
     * <p>SQL 只在本类实现一次，策略类只负责"选哪条路径"，避免三份 SQL 各写一遍而漂移。
     *
     * @param taskId   任务 ID
     * @param owner    持有者
     * @param lease    租约
     * @param forced   强制使用的策略
     * @return 分片
     */
    public Optional<Shard> claimOne(long taskId, String owner, Duration lease,
                                    ClaimStrategy forced) {
        ClaimStrategy chosen = forced == null ? strategy : forced;
        switch (chosen) {
            case SKIP_LOCKED:
                try {
                    return claimSkipLocked(taskId, owner, lease);
                } catch (DataAccessException e) {
                    degrade("SKIP LOCKED", e);
                    return claimOptimisticCas(taskId, owner, lease);
                }
            case NOWAIT_PROBE:
                try {
                    return claimNoWaitProbe(taskId, owner, lease);
                } catch (DataAccessException e) {
                    degrade("FOR UPDATE NOWAIT", e);
                    return claimOptimisticCas(taskId, owner, lease);
                }
            case OPTIMISTIC_CAS:
            default:
                return claimOptimisticCas(taskId, owner, lease);
        }
    }

    /**
     * 候选 SQL 的公共 WHERE 片段（{@link #claimOne} 与 {@link #takeOverExpired} 必须一致）。
     *
     * @return WHERE 片段（不含前导 AND）
     */
    private static String claimablePredicate() {
        return "(state='PENDING' OR (state IN ('CLAIMED','RUNNING') AND lease_expire_at < ?))";
    }

    /**
     * 策略一：{@code SELECT ... LIMIT 1 FOR UPDATE SKIP LOCKED}（O(1) 无冲突）。
     *
     * @param taskId 任务 ID
     * @param owner  持有者
     * @param lease  租约
     * @return 分片
     */
    private Optional<Shard> claimSkipLocked(long taskId, String owner, Duration lease) {
        return tx.execute(new TransactionCallback<Optional<Shard>>() {
            @Override
            public Optional<Shard> doInTransaction(org.springframework.transaction.TransactionStatus st) {
                Instant now = now();
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT id,version FROM sync_shard WHERE task_id=? AND "
                                + claimablePredicate() + " ORDER BY shard_no LIMIT 1 "
                                + "FOR UPDATE SKIP LOCKED",
                        taskId, toTimestamp(now));
                if (rows.isEmpty()) {
                    return Optional.<Shard>empty();
                }
                Map<String, Object> row = rows.get(0);
                long id = ((Number) row.get("id")).longValue();
                int version = ((Number) row.get("version")).intValue();
                if (doClaim(id, owner, lease, now, version) != 1) {
                    return Optional.<Shard>empty();
                }
                return findById(id);
            }
        });
    }

    /**
     * 策略二：先取 32 个候选，再逐条 {@code FOR UPDATE NOWAIT} 探测（DM8 / 老 DB2 降级方案）。
     *
     * @param taskId 任务 ID
     * @param owner  持有者
     * @param lease  租约
     * @return 分片
     */
    private Optional<Shard> claimNoWaitProbe(long taskId, String owner, Duration lease) {
        Instant now = now();
        List<Map<String, Object>> candidates = jdbc.queryForList(
                "SELECT id,version FROM sync_shard WHERE task_id=? AND " + claimablePredicate()
                        + " ORDER BY shard_no LIMIT " + PROBE_CANDIDATE_LIMIT,
                taskId, toTimestamp(now));
        for (Map<String, Object> candidate : candidates) {
            final long id = ((Number) candidate.get("id")).longValue();
            final int version = ((Number) candidate.get("version")).intValue();
            Optional<Shard> claimed = tx.execute(new TransactionCallback<Optional<Shard>>() {
                @Override
                public Optional<Shard> doInTransaction(
                        org.springframework.transaction.TransactionStatus st) {
                    Instant txNow = now();
                    try {
                        List<Map<String, Object>> locked = jdbc.queryForList(
                                "SELECT id FROM sync_shard WHERE id=? FOR UPDATE NOWAIT", id);
                        if (locked.isEmpty()) {
                            return Optional.<Shard>empty();
                        }
                    } catch (DataAccessException busy) {
                        // 资源忙（ORA-00054 / DB2 -911）：换下一个候选
                        log.debug("NOWAIT 探测遇到资源忙 shardId={}：{}", id, busy.getMessage());
                        return Optional.<Shard>empty();
                    }
                    if (doClaim(id, owner, lease, txNow, version) != 1) {
                        return Optional.<Shard>empty();
                    }
                    return findById(id);
                }
            });
            if (claimed != null && claimed.isPresent()) {
                return claimed;
            }
        }
        return Optional.<Shard>empty();
    }

    /**
     * 策略三：无锁乐观 CAS（兜底，任何库可用）。
     *
     * @param taskId 任务 ID
     * @param owner  持有者
     * @param lease  租约
     * @return 分片
     */
    private Optional<Shard> claimOptimisticCas(long taskId, String owner, Duration lease) {
        Instant now = now();
        List<Map<String, Object>> candidates = jdbc.queryForList(
                "SELECT id,version FROM sync_shard WHERE task_id=? AND " + claimablePredicate()
                        + " ORDER BY shard_no LIMIT 8",
                taskId, toTimestamp(now));
        for (Map<String, Object> candidate : candidates) {
            long id = ((Number) candidate.get("id")).longValue();
            int version = ((Number) candidate.get("version")).intValue();
            if (doClaim(id, owner, lease, now, version) == 1) {
                return findById(id);
            }
        }
        return Optional.<Shard>empty();
    }

    /**
     * 执行抢占 UPDATE（带 version 与租约双重校验，防止"最后一刻续租"竞态）。
     *
     * @param id      分片 ID
     * @param owner   持有者
     * @param lease   租约
     * @param now     当前时间（调用方提供，保证同一事务内时间一致）
     * @param version 期望版本
     * @return 影响行数（1 = 抢到）
     */
    private int doClaim(long id, String owner, Duration lease, Instant now, int version) {
        Instant expireAt = now.plus(lease);
        return jdbc.update("UPDATE sync_shard SET state=?,lease_owner=?,lease_expire_at=?,"
                        + "heartbeat_at=?,attempt=attempt+1,version=version+1,updated_at=?,"
                        + "started_at=COALESCE(started_at,?) "
                        + "WHERE id=? AND version=? AND " + claimablePredicate(),
                ShardState.CLAIMED.name(), owner, toTimestamp(expireAt), toTimestamp(now),
                toTimestamp(now), toTimestamp(now), id, version, toTimestamp(now));
    }

    /**
     * 永久降级到乐观 CAS 并告警（只降一次）。
     *
     * @param feature 降级的能力名
     * @param cause   触发异常
     */
    private void degrade(String feature, DataAccessException cause) {
        ClaimStrategy previous = strategy;
        if (previous == ClaimStrategy.OPTIMISTIC_CAS) {
            return;
        }
        strategy = ClaimStrategy.OPTIMISTIC_CAS;
        log.warn("元数据库不支持 {}，分片抢占已降级为 OPTIMISTIC_CAS（原因：{}）",
                feature, cause.getMostSpecificCause().getMessage());
    }

    /**
     * 续租。返回 false 表示租约已被他人持有、已过期或已被接管。
     *
     * @param shardId 分片 ID
     * @param owner   持有者
     * @param lease   租约
     * @return 是否续租成功
     */
    public boolean renewLease(long shardId, String owner, Duration lease) {
        Instant now = now();
        Instant expireAt = now.plus(lease == null ? Duration.ofSeconds(30) : lease);
        int rows = jdbc.update("UPDATE sync_shard SET lease_expire_at=?,heartbeat_at=?,updated_at=? "
                        + "WHERE id=? AND lease_owner=? AND lease_expire_at >= ?",
                toTimestamp(expireAt), toTimestamp(now), toTimestamp(now), shardId, owner,
                toTimestamp(now));
        return rows == 1;
    }

    /**
     * 校验租约仍归自己所有（每 N 批调用一次，架构 §4.2.5）。
     *
     * @param shardId 分片 ID
     * @param owner   持有者
     * @return 是否仍持有
     */
    public boolean assertHeld(long shardId, String owner) {
        Integer n = jdbc.queryForObject("SELECT COUNT(1) FROM sync_shard WHERE id=? "
                + "AND lease_owner=? AND lease_expire_at >= ?", Integer.class,
                shardId, owner, toTimestamp(now()));
        return n != null && n > 0;
    }

    /**
     * 主动释放（best effort，不抛异常；架构 §4.2.6 优雅停机）。
     *
     * @param shardId 分片 ID
     * @param owner   持有者
     */
    public void release(long shardId, String owner) {
        try {
            jdbc.update("UPDATE sync_shard SET state=?,lease_owner=NULL,lease_expire_at=NULL,"
                            + "version=version+1,updated_at=? WHERE id=? AND lease_owner=?",
                    ShardState.PENDING.name(), toTimestamp(now()), shardId, owner);
        } catch (DataAccessException e) {
            // 释放失败不是错误：租约过期后会被其他实例接管
            log.debug("释放分片失败（可忽略）shardId={}：{}", shardId, e.getMessage());
        }
    }

    /**
     * 回收过期租约（默认 30s 新租约）。
     *
     * @param taskId 任务 ID
     * @param owner  新持有者
     * @return 回收数量
     */
    public int takeOverExpired(long taskId, String owner) {
        return takeOverExpired(taskId, owner, Duration.ofSeconds(30));
    }

    /**
     * 把过期租约的分片<b>放回 PENDING</b>（租约回收任务用）。
     *
     * <p>// ADDED: 04 文档 T44 验收 1 明确要求"reaper 回收后 {@code state='PENDING'}、
     * {@code lease_owner=NULL}"，而 {@link #takeOverExpired} 的语义是"直接换持有者"
     * （给 {@code MigrationEngine.recover} 用）。两者语义不同，故分成两个方法：
     * <ul>
     *   <li>{@code takeOverExpired}：新实例直接接管，lease 立即续上（启动恢复路径）；</li>
     *   <li>本方法：放回公池，等任意实例 {@code claimOne} 抢（周期回收路径），
     *       让负载能重新均衡，而不是被"第一个发现者"全部拿走。</li>
     * </ul>
     *
     * <p><b>判据与 {@link #claimOne} 一致</b>：{@code state IN ('CLAIMED','RUNNING')
     * AND lease_expire_at < now}——正是 {@code claimablePredicate()} 的第二个分支，
     * 因此"回收后立即可抢"由定义保证。
     *
     * @param taskId 任务 ID
     * @return 回收（置 PENDING）的分片数
     */
    public int releaseExpiredToPending(long taskId) {
        Instant now = now();
        int rows = jdbc.update("UPDATE sync_shard SET state='PENDING',lease_owner=NULL,"
                        + "lease_expire_at=NULL,version=version+1,updated_at=? "
                        + "WHERE task_id=? AND state IN ('CLAIMED','RUNNING') AND lease_expire_at < ?",
                toTimestamp(now), taskId, toTimestamp(now));
        if (rows > 0) {
            log.info("过期租约已放回 PENDING taskId={} count={}", taskId, rows);
        }
        return rows;
    }

    /**
     * 回收过期租约（实例宕机后的接管扫描），返回回收数量。
     *
     * <p>判据必须与 {@link #claimOne} 完全一致（同一表达式、同一时间来源 now）。
     * <b>接管后必须写入新的 lease_expire_at</b>，否则这些行仍满足
     * {@code lease_expire_at < now}，会被第三个实例立刻再抢一次。
     *
     * @param taskId 任务 ID
     * @param owner  新持有者
     * @param lease  新租约时长
     * @return 回收数量
     */
    public int takeOverExpired(long taskId, String owner, Duration lease) {
        Instant now = now();
        Duration effective = lease == null || lease.isZero() || lease.isNegative()
                ? Duration.ofSeconds(30) : lease;
        Instant expireAt = now.plus(effective);
        int rows = jdbc.update("UPDATE sync_shard SET lease_owner=?,lease_expire_at=?,"
                        + "heartbeat_at=?,attempt=attempt+1,version=version+1,updated_at=? "
                        + "WHERE task_id=? AND state IN ('CLAIMED','RUNNING') "
                        + "AND lease_expire_at < ?",
                owner, toTimestamp(expireAt), toTimestamp(now), toTimestamp(now), taskId,
                toTimestamp(now));
        if (rows > 0) {
            log.info("回收过期租约 taskId={} newOwner={} count={}", taskId, owner, rows);
        }
        return rows;
    }

    /**
     * 更新进度（带租约校验 + 版本递增）。
     *
     * @param shardId       分片 ID
     * @param owner         持有者
     * @param lastPk        已提交的最后主键
     * @param committedRows 已提交行数
     * @param version       期望版本
     * @return 影响行数（0 表示租约已丢，调用方必须停止）
     */
    public int updateProgress(long shardId, String owner, String lastPk, long committedRows,
                              int version) {
        return jdbc.update("UPDATE sync_shard SET last_pk=?,committed_rows=?,version=version+1,"
                        + "updated_at=? WHERE id=? AND lease_owner=? AND version=?",
                lastPk, committedRows, toTimestamp(now()), shardId, owner, version);
    }

    /**
     * 无条件回写进度快照（<b>仅供 {@code CheckpointStore} 在自身事务内调用</b>）。
     *
     * <p>与 {@link #updateProgress} 的区别：本方法不带 owner/version 校验。
     * 之所以敢这样，是因为调用点严格限定在"刚提交完数据、仍在持有租约的同一线程"，
     * 且与 {@code sync_checkpoint} 的写入在<b>同一个元数据库事务</b>里（架构 I1 的落地方式）。
     *
     * @param shardId       分片 ID
     * @param lastPk        已提交的最后主键
     * @param committedRows 已提交行数
     * @return 影响行数
     */
    public int updateProgressSnapshot(long shardId, String lastPk, long committedRows) {
        return jdbc.update("UPDATE sync_shard SET last_pk=?,committed_rows=?,version=version+1,"
                        + "updated_at=? WHERE id=?",
                lastPk, committedRows, toTimestamp(now()), shardId);
    }

    /**
     * 置为运行中。
     *
     * @param shardId 分片 ID
     * @param owner   持有者
     * @return 影响行数
     */
    public int markRunning(long shardId, String owner) {
        return jdbc.update("UPDATE sync_shard SET state=?,version=version+1,updated_at=? "
                        + "WHERE id=? AND lease_owner=?",
                ShardState.RUNNING.name(), toTimestamp(now()), shardId, owner);
    }

    /**
     * 置为成功并清空租约（源侧行数沿用已提交行数，保留给不需要区分两者的调用方）。
     *
     * @param shardId       分片 ID
     * @param owner         持有者
     * @param committedRows 最终行数
     * @return 影响行数
     */
    public int markSuccess(long shardId, String owner, long committedRows) {
        return markSuccess(shardId, owner, committedRows, committedRows);
    }

    /**
     * 置为成功并清空租约，<b>同时写入源侧实际读取行数</b>。
     *
     * <p>为什么两个行数必须分开写：按表进度（MIG-06）的百分比算的是
     * {@code committedRows / sourceRows}，而 {@code sourceRows} 原先全平台无人写入
     * （列默认 -1），导致 {@code percent} 恒为 null、前端永远显示"—"。
     *
     * <p>为什么不能用 {@code committedRows} 冒充 {@code sourceRows}：
     * 无主键降级表的分片区间无法二分，"读到的行数"可能大于"真正落库的行数"
     * （重复行靠 upsert 幂等覆盖），用提交数冒充会让百分比恒为 100%，
     * 把"漏数"这类最需要被看见的问题掩盖掉。因此这里如实记录读到的行数。
     *
     * @param shardId       分片 ID
     * @param owner         持有者
     * @param committedRows 最终行数（已提交）
     * @param sourceRows    源侧实际读取行数（≤0 表示未知，按表进度会退回估算值）
     * @return 影响行数
     */
    public int markSuccess(long shardId, String owner, long committedRows, long sourceRows) {
        Instant now = now();
        return jdbc.update("UPDATE sync_shard SET state=?,committed_rows=?,source_rows=?,"
                        + "finished_at=?,lease_owner=NULL,lease_expire_at=NULL,version=version+1,"
                        + "updated_at=? WHERE id=? AND lease_owner=?",
                ShardState.SUCCESS.name(), committedRows, sourceRows, toTimestamp(now),
                toTimestamp(now), shardId, owner);
    }

    /**
     * 置为失败并记录错误（保留 attempt 供重试判定）。
     *
     * @param shardId 分片 ID
     * @param owner   持有者
     * @param error   错误摘要
     * @return 影响行数
     */
    public int markFailed(long shardId, String owner, String error) {
        Instant now = now();
        return jdbc.update("UPDATE sync_shard SET state=?,last_error=?,lease_owner=NULL,"
                        + "lease_expire_at=NULL,finished_at=?,version=version+1,updated_at=? "
                        + "WHERE id=? AND lease_owner=?",
                ShardState.FAILED.name(), truncate(error, 1000), toTimestamp(now),
                toTimestamp(now), shardId, owner);
    }

    /**
     * 置为死信（毒分片：接管次数超限）。
     *
     * @param shardId 分片 ID
     * @param error   错误摘要
     * @return 影响行数
     */
    public int markDead(long shardId, String error) {
        return jdbc.update("UPDATE sync_shard SET state=?,last_error=?,lease_owner=NULL,"
                        + "lease_expire_at=NULL,version=version+1,updated_at=? WHERE id=?",
                ShardState.DEAD.name(), truncate(error, 1000), toTimestamp(now()), shardId);
    }

    /**
     * 重置为待迁移（失败重试 / 任务重启）。
     *
     * @param shardId 分片 ID
     * @return 影响行数
     */
    public int resetPending(long shardId) {
        return jdbc.update("UPDATE sync_shard SET state=?,lease_owner=NULL,lease_expire_at=NULL,"
                        + "version=version+1,updated_at=? WHERE id=?",
                ShardState.PENDING.name(), toTimestamp(now()), shardId);
    }

    /**
     * 按状态统计（进度上报 / Gate 判定用）。
     *
     * @param taskId 任务 ID
     * @return 状态 → 数量（所有枚举值都放进去，缺省 0）
     */
    public Map<ShardState, Integer> countByState(long taskId) {
        Map<ShardState, Integer> result = new EnumMap<ShardState, Integer>(ShardState.class);
        for (ShardState state : ShardState.values()) {
            result.put(state, 0);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT state, COUNT(1) AS cnt FROM sync_shard WHERE task_id=? GROUP BY state",
                taskId);
        for (Map<String, Object> row : rows) {
            Object stateName = row.get("state");
            Object cnt = row.get("cnt");
            if (stateName == null || cnt == null) {
                continue;
            }
            ShardState key;
            try {
                key = ShardState.valueOf(String.valueOf(stateName));
            } catch (IllegalArgumentException e) {
                log.warn("未知分片状态，忽略：{}", stateName);
                continue;
            }
            result.put(key, ((Number) cnt).intValue());
        }
        return result;
    }

    /**
     * 任务内已提交总行数。
     *
     * @param taskId 任务 ID
     * @return 总行数
     */
    public long sumCommittedRows(long taskId) {
        Long n = jdbc.queryForObject("SELECT COALESCE(SUM(committed_rows),0) FROM sync_shard "
                + "WHERE task_id=?", Long.class, taskId);
        return n == null ? 0L : n;
    }

    /**
     * 任务内下一个可用的分片序号。
     *
     * @param taskId 任务 ID
     * @return 序号（无分片返回 0）
     */
    public int nextShardNo(long taskId) {
        Integer n = jdbc.queryForObject("SELECT COALESCE(MAX(shard_no),-1) FROM sync_shard "
                + "WHERE task_id=?", Integer.class, taskId);
        return n == null ? 0 : n + 1;
    }

    /**
     * 删除任务下全部分片（重新规划前清理）。
     *
     * @param taskId 任务 ID
     * @return 影响行数
     */
    public int deleteByTask(long taskId) {
        return jdbc.update("DELETE FROM sync_shard WHERE task_id=?", taskId);
    }

    /**
     * 字符串截断（DB 列宽有限，错误摘要不能无限长）。
     *
     * @param value 原值
     * @param max   最大长度
     * @return 截断后的值
     */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
