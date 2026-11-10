package com.dbsync.metadata.dao;

import com.dbsync.common.enums.ChangeOp;
import com.dbsync.common.enums.ChangeStatus;
import com.dbsync.common.model.ChangeEvent;
import com.dbsync.common.util.JsonUtil;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 变更日志（CDC 队列）DAO —— 保序与水位的核心（架构 §4.1.4）。
 *
 * <p><b>I4 不可违反</b>：{@link #safeSeq(long, int)} 必须是
 * {@code MIN(seq) - 1 WHERE status='NEW'}，<b>绝不能</b>用 {@code MAX(已应用 seq)}。
 * 原因：seq 1,2,3 中若 2 未应用而 3 已应用，用 {@code MAX} 会把水位推到 3，
 * 之后 2 永远不会被重放（数据永久丢失）；用 {@code MIN-1} 则水位停在 1，
 * 消费端会继续从 2 开始重试，重复应用是幂等 Upsert 可以容忍的，丢失不可容忍。
 */
@Repository
public class ChangeLogDao extends AbstractMetaDao {

    /** 单条 {@code IN (...)} 的最大参数个数（避免超出部分驱动的参数上限）。 */
    private static final int IN_CHUNK = 200;

    private final RowMapper<ChangeEvent> mapper = new RowMapper<ChangeEvent>() {
        @Override
        public ChangeEvent mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ChangeEvent(rs.getLong("id"), rs.getLong("task_id"),
                    rs.getLong("table_mapping_id"), rs.getInt("group_no"), rs.getLong("pk_hash"),
                    rs.getLong("seq"), instantOf(rs, "commit_ts"),
                    ChangeOp.valueOf(rs.getString("op")),
                    JsonUtil.parseArray(rs.getString("pk_json")),
                    JsonUtil.parseObject(rs.getString("before_json")),
                    JsonUtil.parseObject(rs.getString("after_json")),
                    splitColumns(rs.getString("changed_columns")),
                    ChangeStatus.valueOf(rs.getString("status")), rs.getInt("retry"),
                    rs.getString("error_msg"), rs.getString("conflict_action"));
        }
    };

    /**
     * 构造。
     *
     * @param jdbc 元数据库 JdbcTemplate
     */
    public ChangeLogDao(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc) {
        super(jdbc);
    }

    /**
     * 写入一条变更（触发器轮询器把源库日志搬进元数据库）。
     *
     * @param event    变更事件
     * @param traceId  链路 ID（可 null）
     * @param instance 源实例标识（可 null）
     * @return 影响行数
     */
    public int insert(ChangeEvent event, String traceId, String instance) {
        return jdbc.update("INSERT INTO sync_change_log (id,task_id,table_mapping_id,group_no,"
                        + "pk_hash,seq,commit_ts,op,pk_json,before_json,after_json,changed_columns,"
                        + "status,retry,source_instance,trace_id,created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                event.getId(), event.getTaskId(), event.getTableMappingId(), event.getGroupNo(),
                event.getPkHash(), event.getSeq(), toTimestamp(event.getCommitTs()),
                event.getOp().name(), JsonUtil.toJson(event.getPkTuple()),
                JsonUtil.toJson(event.getBefore()), JsonUtil.toJson(event.getAfter()),
                joinColumns(event.getChangedColumns()), event.getStatus().name(),
                event.getRetry(), instance, traceId, toTimestamp(now()));
    }

    /**
     * 批量写入变更（一次轮询多行，减少往返）。
     *
     * @param events 事件列表
     * @return 影响行数
     */
    public int insertBatch(List<ChangeEvent> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        Instant createdAt = now();
        List<Object[]> args = new ArrayList<Object[]>(events.size());
        for (ChangeEvent e : events) {
            args.add(new Object[] {e.getId(), e.getTaskId(), e.getTableMappingId(), e.getGroupNo(),
                    e.getPkHash(), e.getSeq(), toTimestamp(e.getCommitTs()), e.getOp().name(),
                    JsonUtil.toJson(e.getPkTuple()), JsonUtil.toJson(e.getBefore()),
                    JsonUtil.toJson(e.getAfter()), joinColumns(e.getChangedColumns()),
                    e.getStatus().name(), e.getRetry(), createdAt});
        }
        int[] rows = jdbc.batchUpdate("INSERT INTO sync_change_log (id,task_id,table_mapping_id,"
                + "group_no,pk_hash,seq,commit_ts,op,pk_json,before_json,after_json,"
                + "changed_columns,status,retry,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                args);
        int sum = 0;
        for (int r : rows) {
            sum += r;
        }
        return sum;
    }

    /**
     * 取一批待消费变更（走 {@code idx_consume}，ORDER BY seq 保证组内有序）。
     *
     * @param taskId  任务 ID
     * @param groupNo 组号
     * @param limit   批量大小
     * @return 事件列表
     */
    public List<ChangeEvent> pollBatch(long taskId, int groupNo, int limit) {
        int effective = limit <= 0 ? 100 : limit;
        return jdbc.query("SELECT * FROM sync_change_log WHERE task_id=? AND group_no=? "
                        + "AND status=? ORDER BY seq LIMIT " + effective,
                mapper, taskId, groupNo, ChangeStatus.NEW.name());
    }

    /**
     * 批量置为已应用/已跳过（一条 UPDATE ... WHERE id IN (...)，分片避免参数超限）。
     *
     * @param ids    日志 ID 列表
     * @param status 目标状态
     * @return 影响行数
     */
    public int markApplied(List<Long> ids, ChangeStatus status) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        Instant now = now();
        int sum = 0;
        for (int from = 0; from < ids.size(); from += IN_CHUNK) {
            List<Long> chunk = ids.subList(from, Math.min(from + IN_CHUNK, ids.size()));
            StringBuilder sql = new StringBuilder(
                    "UPDATE sync_change_log SET status=?,applied_at=? WHERE id IN (");
            List<Object> args = new ArrayList<Object>(chunk.size() + 2);
            args.add(status.name());
            args.add(toTimestamp(now));
            appendPlaceholders(sql, args, chunk);
            sql.append(')');
            sum += jdbc.update(sql.toString(), args.toArray());
        }
        return sum;
    }

    /**
     * 置为死信（超过重试上限或不可重试错误）。
     *
     * @param id       日志 ID
     * @param errorMsg 错误信息
     * @return 影响行数
     */
    public int markDead(long id, String errorMsg) {
        return jdbc.update("UPDATE sync_change_log SET status=?,error_msg=? WHERE id=?",
                ChangeStatus.DEAD.name(), truncate(errorMsg, 1000), id);
    }

    /**
     * 置为"已跳过"并记录冲突动作。
     *
     * <p>// ADDED: 04 文档 T30 要求 {@code TARGET_WINS} 跳过时
     * {@code conflict_action='TARGET_WINS_IGNORED'}，但文档列出的 DAO 方法里没有写
     * {@code conflict_action} 的入口。单独成一个方法而不是给 {@code markApplied} 加可选参数：
     * 冲突动作是"策略审计"信息，只在跳过时才有意义，混进批量 UPDATE 会让 12 个调用点都要考虑它。
     *
     * @param ids            日志 ID 列表
     * @param conflictAction 冲突动作
     * @return 影响行数
     */
    public int markSkipped(List<Long> ids, String conflictAction) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        Instant now = now();
        int sum = 0;
        for (int from = 0; from < ids.size(); from += IN_CHUNK) {
            List<Long> chunk = ids.subList(from, Math.min(from + IN_CHUNK, ids.size()));
            StringBuilder sql = new StringBuilder("UPDATE sync_change_log SET status=?,"
                    + "conflict_action=?,applied_at=? WHERE id IN (");
            List<Object> args = new ArrayList<Object>(chunk.size() + 3);
            args.add(ChangeStatus.SKIPPED.name());
            args.add(truncate(conflictAction, 24));
            args.add(toTimestamp(now));
            appendPlaceholders(sql, args, chunk);
            sql.append(')');
            sum += jdbc.update(sql.toString(), args.toArray());
        }
        return sum;
    }

    /**
     * 记录一次失败并累加重试次数。
     *
     * @param id       日志 ID
     * @param errorMsg 错误信息
     * @return 影响行数
     */
    public int markRetry(long id, String errorMsg) {
        return jdbc.update("UPDATE sync_change_log SET retry=retry+1,error_msg=? WHERE id=?",
                truncate(errorMsg, 1000), id);
    }

    /**
     * 组水位线：连续已应用前缀。
     *
     * <p><b>I4</b>：{@code MIN(seq)-1 WHERE status='NEW'}；组内无 NEW 时返回该组 {@code MAX(seq)}。
     *
     * @param taskId  任务 ID
     * @param groupNo 组号
     * @return 安全序号（无数据返回 0）
     */
    public long safeSeq(long taskId, int groupNo) {
        Long minUnapplied = jdbc.queryForObject("SELECT MIN(seq) FROM sync_change_log "
                        + "WHERE task_id=? AND group_no=? AND status=?",
                Long.class, taskId, groupNo, ChangeStatus.NEW.name());
        if (minUnapplied != null) {
            return minUnapplied - 1L;
        }
        Long maxSeq = jdbc.queryForObject("SELECT MAX(seq) FROM sync_change_log "
                + "WHERE task_id=? AND group_no=?", Long.class, taskId, groupNo);
        if (maxSeq == null) {
            return 0L;
        }
        return maxSeq;
    }

    /**
     * 安全水位的提交时间（用于 lag 计算与"读窗口"判定）。
     *
     * @param taskId   任务 ID
     * @param groupNo  组号
     * @param safeSeq  安全序号
     * @return 该序号的 commit_ts；无记录返回 null
     */
    public Instant safeCommitTs(long taskId, int groupNo, long safeSeq) {
        List<Instant> list = jdbc.query("SELECT commit_ts FROM sync_change_log "
                        + "WHERE task_id=? AND group_no=? AND seq=? LIMIT 1",
                new RowMapper<Instant>() {
                    @Override
                    public Instant mapRow(ResultSet rs, int rowNum) throws SQLException {
                        return instantOf(rs, "commit_ts");
                    }
                }, taskId, groupNo, safeSeq);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 任务积压量（status=NEW 的行数）。
     *
     * @param taskId 任务 ID
     * @return 积压行数
     */
    public long backlog(long taskId) {
        Long n = jdbc.queryForObject("SELECT COUNT(1) FROM sync_change_log "
                + "WHERE task_id=? AND status=?", Long.class, taskId, ChangeStatus.NEW.name());
        return n == null ? 0L : n;
    }

    /**
     * 组积压量。
     *
     * @param taskId  任务 ID
     * @param groupNo 组号
     * @return 积压行数
     */
    public long groupBacklog(long taskId, int groupNo) {
        Long n = jdbc.queryForObject("SELECT COUNT(1) FROM sync_change_log WHERE task_id=? "
                + "AND group_no=? AND status=?", Long.class, taskId, groupNo,
                ChangeStatus.NEW.name());
        return n == null ? 0L : n;
    }

    /**
     * 最老的未应用变更提交时间（增量延迟 p95 的底座数据）。
     *
     * @param taskId 任务 ID
     * @return 提交时间；无未应用记录返回 null
     */
    public Instant oldestUnappliedCommitTs(long taskId) {
        List<Instant> list = jdbc.query("SELECT MIN(commit_ts) AS ts FROM sync_change_log "
                        + "WHERE task_id=? AND status=?", new RowMapper<Instant>() {
                    @Override
                    public Instant mapRow(ResultSet rs, int rowNum) throws SQLException {
                        return instantOf(rs, "ts");
                    }
                }, taskId, ChangeStatus.NEW.name());
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 按 ID 查询单条。
     *
     * @param id 日志 ID
     * @return 事件
     */
    public Optional<ChangeEvent> findById(long id) {
        List<ChangeEvent> list = jdbc.query("SELECT * FROM sync_change_log WHERE id=?",
                mapper, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 按状态统计（监控用）。
     *
     * @param taskId 任务 ID
     * @param status 状态
     * @return 行数
     */
    public long countByStatus(long taskId, ChangeStatus status) {
        Long n = jdbc.queryForObject("SELECT COUNT(1) FROM sync_change_log WHERE task_id=? "
                + "AND status=?", Long.class, taskId, status.name());
        return n == null ? 0L : n;
    }

    /**
     * 按主键追溯最近若干条变更（T32 追溯查询）。
     *
     * <p>// ADDED: 04 文档 T32 要求"按主键追溯最近 20 条"且走 {@code idx_trace}，
     * 原有 DAO 无此查询。条件顺序严格对齐索引 {@code (task_id, table_mapping_id, pk_hash, seq)}，
     * 因此 {@code EXPLAIN} 命中 {@code idx_trace}，不会 filesort。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @param pkHashes       主键哈希候选（同一逻辑主键的不同类型规范化结果）
     * @param limit          上限
     * @return 变更事件（按 seq 倒序）
     */
    public List<ChangeEvent> trace(long taskId, long tableMappingId, List<Long> pkHashes,
                                   int limit) {
        if (pkHashes == null || pkHashes.isEmpty()) {
            return List.of();
        }
        int effective = limit <= 0 ? 20 : Math.min(limit, 200);
        StringBuilder placeholders = new StringBuilder();
        Object[] args = new Object[pkHashes.size() + 2];
        args[0] = Long.valueOf(taskId);
        args[1] = Long.valueOf(tableMappingId);
        for (int i = 0; i < pkHashes.size(); i++) {
            placeholders.append(i == 0 ? "?" : ",?");
            args[i + 2] = pkHashes.get(i);
        }
        return jdbc.query("SELECT * FROM sync_change_log WHERE task_id=? AND table_mapping_id=? "
                + "AND pk_hash IN (" + placeholders + ") ORDER BY seq DESC LIMIT " + effective,
                mapper, args);
    }

    /**
     * 按状态统计指定时间之后的条数（失败率窗口分子）。
     *
     * <p>// ADDED: {@code dbsync.cdc.apply.fail.rate} 需要"最近 15 分钟"口径（架构 §11），
     * 原有 {@link #countByStatus} 无时间窗。
     *
     * @param taskId 任务 ID
     * @param status 状态
     * @param since  起始时间（含）
     * @return 条数
     */
    public long countByStatusSince(long taskId, ChangeStatus status, Instant since) {
        Long n = jdbc.queryForObject("SELECT COUNT(1) FROM sync_change_log WHERE task_id=? "
                + "AND status=? AND commit_ts>=?", Long.class, taskId, status.name(),
                toTimestamp(since));
        return n == null ? 0L : n;
    }

    /**
     * 统计窗口内"已处理"条数（APPLIED + FAILED + SKIPPED，<b>不含 DEAD</b>）。
     *
     * <p>// ADDED: 架构 §11 明确要求失败率分母不含 DEAD（DEAD 单独看死信指标）。
     *
     * @param taskId 任务 ID
     * @param since  起始时间（含）
     * @return 条数
     */
    public long countFinishedSince(long taskId, Instant since) {
        Long n = jdbc.queryForObject("SELECT COUNT(1) FROM sync_change_log WHERE task_id=? "
                        + "AND status IN (?,?,?) AND commit_ts>=?", Long.class, taskId,
                ChangeStatus.APPLIED.name(), ChangeStatus.FAILED.name(),
                ChangeStatus.SKIPPED.name(), toTimestamp(since));
        return n == null ? 0L : n;
    }

    /**
     * 清理已应用且早于给定时间的历史日志（删除，供 {@code ChangeLogArchiver} 调用）。
     *
     * @param taskId     任务 ID
     * @param before     时间上界
     * @param batchLimit 单次最多删除行数
     * @return 实际删除行数
     */
    public int deleteAppliedBefore(long taskId, Instant before, int batchLimit) {
        int limit = batchLimit <= 0 ? 5000 : batchLimit;
        // 先用可移植的 LIMIT 子查询取 ID，再按 ID 删除（MySQL 与 H2 都支持）
        List<Long> ids = jdbc.queryForList("SELECT id FROM sync_change_log WHERE task_id=? "
                        + "AND status IN (?,?) AND created_at < ? LIMIT " + limit,
                Long.class, taskId, ChangeStatus.APPLIED.name(), ChangeStatus.SKIPPED.name(),
                toTimestamp(before));
        return deleteByIds(ids);
    }

    /**
     * 清理<b>跨任务</b>已应用且早于给定时间的历史日志（{@link ChangeLogArchiver} 定时任务用）。
     *
     * @param before     时间上界
     * @param batchLimit 单次最多删除行数
     * @return 实际删除行数
     */
    public int deleteAppliedBefore(Instant before, int batchLimit) {
        int limit = batchLimit <= 0 ? 5000 : batchLimit;
        List<Long> ids = jdbc.queryForList("SELECT id FROM sync_change_log WHERE "
                        + "status IN (?,?) AND created_at < ? LIMIT " + limit,
                Long.class, ChangeStatus.APPLIED.name(), ChangeStatus.SKIPPED.name(),
                toTimestamp(before));
        return deleteByIds(ids);
    }

    /**
     * 删除任务下全部变更日志（任务重置用）。
     *
     * @param taskId 任务 ID
     * @return 影响行数
     */
    public int deleteByTask(long taskId) {
        return jdbc.update("DELETE FROM sync_change_log WHERE task_id=?", taskId);
    }

    /**
     * 按 ID 批量删除（内部分片，避免参数超限）。
     *
     * @param ids ID 列表
     * @return 删除行数
     */
    private int deleteByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (int from = 0; from < ids.size(); from += IN_CHUNK) {
            List<Long> chunk = ids.subList(from, Math.min(from + IN_CHUNK, ids.size()));
            StringBuilder sql = new StringBuilder("DELETE FROM sync_change_log WHERE id IN (");
            List<Object> args = new ArrayList<Object>(chunk.size());
            appendPlaceholders(sql, args, chunk);
            sql.append(')');
            sum += jdbc.update(sql.toString(), args.toArray());
        }
        return sum;
    }

    /**
     * 拼接占位符 + 收集参数。
     *
     * @param sql   SQL 缓冲
     * @param args  参数收集器
     * @param chunk ID 列表
     */
    private static void appendPlaceholders(StringBuilder sql, List<Object> args, List<Long> chunk) {
        for (int i = 0; i < chunk.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('?');
            args.add(chunk.get(i));
        }
    }

    /**
     * 列名列表 → 逗号分隔串。
     *
     * @param columns 列名
     * @return 串；空返回 null
     */
    private static String joinColumns(List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return null;
        }
        return String.join(",", columns);
    }

    /**
     * 逗号分隔串 → 列名列表。
     *
     * @param value 串
     * @return 列名列表（永不为 null）
     */
    private static List<String> splitColumns(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        String[] parts = value.split(",");
        List<String> result = new ArrayList<String>(parts.length);
        for (String part : parts) {
            if (!part.isEmpty()) {
                result.add(part);
            }
        }
        return result;
    }

    /**
     * 字符串截断。
     *
     * @param value 原值
     * @param max   最大长度
     * @return 截断值
     */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
