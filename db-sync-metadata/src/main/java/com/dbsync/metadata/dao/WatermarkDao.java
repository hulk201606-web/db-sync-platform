package com.dbsync.metadata.dao;

import com.dbsync.common.model.Watermark;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 组水位线 DAO（架构 §4.1.4）。
 *
 * <p>一行 = 一个 (task, group_no)；承载三件事：
 * <ol>
 *   <li>组独占：{@code consumer_owner + owner_expire_at}（同一组同一时刻只能有一个消费者，保证组内串行）；</li>
 *   <li>连续前缀水位：{@code safe_seq}（由 {@link ChangeLogDao#safeSeq} 计算）；</li>
 *   <li>延迟观测：{@code lag_ms}（Gate 的 INCREMENT_LAG_P95 数据源）。</li>
 * </ol>
 */
@Repository
public class WatermarkDao extends AbstractMetaDao {

    private final RowMapper<Watermark> mapper = new RowMapper<Watermark>() {
        @Override
        public Watermark mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Watermark(rs.getLong("task_id"), rs.getInt("group_no"),
                    rs.getLong("safe_seq"), instantOf(rs, "safe_commit_ts"),
                    rs.getString("consumer_owner"), rs.getLong("lag_ms"));
        }
    };

    /**
     * 构造。
     *
     * @param jdbc 元数据库 JdbcTemplate
     */
    public WatermarkDao(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc) {
        super(jdbc);
    }

    /**
     * 读取组水位。
     *
     * @param taskId  任务 ID
     * @param groupNo 组号
     * @return 水位；不存在返回 empty
     */
    public Optional<Watermark> load(long taskId, int groupNo) {
        List<Watermark> list = jdbc.query("SELECT * FROM sync_change_watermark WHERE task_id=? "
                + "AND group_no=?", mapper, taskId, groupNo);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 确保水位行存在（幂等；并发冲突忽略）。
     *
     * @param taskId  任务 ID
     * @param groupNo 组号
     * @return 影响行数
     */
    public int ensureExists(long taskId, int groupNo) {
        if (load(taskId, groupNo).isPresent()) {
            return 0;
        }
        try {
            return jdbc.update("INSERT INTO sync_change_watermark (id,task_id,group_no,safe_seq,"
                            + "lag_ms,version,updated_at) VALUES (?,?,?,0,0,0,?)",
                    nextId(), taskId, groupNo, toTimestamp(now()));
        } catch (DuplicateKeyException race) {
            return 0;
        }
    }

    /**
     * 抢占组独占权（{@code consumer_owner} 为空/已过期/本来就是我 时才成功）。
     *
     * @param taskId  任务 ID
     * @param groupNo 组号
     * @param owner   消费者标识
     * @param lease   独占租约
     * @return 是否抢到
     */
    public boolean tryAcquire(long taskId, int groupNo, String owner, Duration lease) {
        ensureExists(taskId, groupNo);
        Instant now = now();
        Instant expireAt = now.plus(lease == null ? Duration.ofSeconds(30) : lease);
        int rows = jdbc.update("UPDATE sync_change_watermark SET consumer_owner=?,"
                        + "owner_expire_at=?,version=version+1,updated_at=? "
                        + "WHERE task_id=? AND group_no=? AND (consumer_owner IS NULL "
                        + "OR consumer_owner=? OR owner_expire_at IS NULL OR owner_expire_at < ?)",
                owner, toTimestamp(expireAt), toTimestamp(now), taskId, groupNo, owner,
                toTimestamp(now));
        return rows == 1;
    }

    /**
     * 续租（只续自己持有的）。
     *
     * @param taskId  任务 ID
     * @param groupNo 组号
     * @param owner   消费者标识
     * @param lease   租约
     * @return 是否仍持有
     */
    public boolean renew(long taskId, int groupNo, String owner, Duration lease) {
        Instant now = now();
        Instant expireAt = now.plus(lease == null ? Duration.ofSeconds(30) : lease);
        int rows = jdbc.update("UPDATE sync_change_watermark SET owner_expire_at=?,updated_at=? "
                        + "WHERE task_id=? AND group_no=? AND consumer_owner=?",
                toTimestamp(expireAt), toTimestamp(now), taskId, groupNo, owner);
        return rows == 1;
    }

    /**
     * 释放组独占权。
     *
     * @param taskId  任务 ID
     * @param groupNo 组号
     * @param owner   消费者标识
     * @return 影响行数
     */
    public int release(long taskId, int groupNo, String owner) {
        return jdbc.update("UPDATE sync_change_watermark SET consumer_owner=NULL,"
                        + "owner_expire_at=NULL,updated_at=? WHERE task_id=? AND group_no=? "
                        + "AND consumer_owner=?",
                toTimestamp(now()), taskId, groupNo, owner);
    }

    /**
     * 推进水位（只能前进：{@code safe_seq < ?} 才写入，避免回退）。
     *
     * @param taskId      任务 ID
     * @param groupNo     组号
     * @param safeSeq     新水位
     * @param safeCommitTs 水位对应提交时间（可 null）
     * @param lagMs       当前延迟
     * @return 影响行数
     */
    public int advance(long taskId, int groupNo, long safeSeq, Instant safeCommitTs, long lagMs) {
        ensureExists(taskId, groupNo);
        return jdbc.update("UPDATE sync_change_watermark SET safe_seq=?,safe_commit_ts=?,lag_ms=?,"
                        + "version=version+1,updated_at=? WHERE task_id=? AND group_no=? "
                        + "AND safe_seq < ?",
                safeSeq, toTimestamp(safeCommitTs), Math.max(0L, lagMs), toTimestamp(now()),
                taskId, groupNo, safeSeq);
    }

    /**
     * 只更新延迟指标（不推进水位，供监控写入）。
     *
     * @param taskId  任务 ID
     * @param groupNo 组号
     * @param lagMs   延迟
     * @return 影响行数
     */
    public int updateLag(long taskId, int groupNo, long lagMs) {
        return jdbc.update("UPDATE sync_change_watermark SET lag_ms=?,updated_at=? "
                        + "WHERE task_id=? AND group_no=?",
                Math.max(0L, lagMs), toTimestamp(now()), taskId, groupNo);
    }

    /**
     * 列出任务下全部组水位。
     *
     * @param taskId 任务 ID
     * @return 水位列表
     */
    public List<Watermark> findByTask(long taskId) {
        return jdbc.query("SELECT * FROM sync_change_watermark WHERE task_id=? ORDER BY group_no",
                mapper, taskId);
    }

    /**
     * 列出未被任何活跃消费者持有的组（空闲组可被本实例认领）。
     *
     * @param taskId 任务 ID
     * @return 水位列表
     */
    public List<Watermark> findFreeGroups(long taskId) {
        return jdbc.query("SELECT * FROM sync_change_watermark WHERE task_id=? "
                        + "AND (consumer_owner IS NULL OR owner_expire_at IS NULL "
                        + "OR owner_expire_at < ?) ORDER BY group_no",
                mapper, taskId, toTimestamp(now()));
    }

    /**
     * 是否<b>有任一实例</b>正在消费该任务（跨实例判断）。
     *
     * <p>判据与 {@link #findFreeGroups} 严格互补：{@code consumer_owner} 非空
     * <b>且</b> {@code owner_expire_at} 未过期。只看 {@code consumer_owner} 非空是不够的——
     * 实例被 kill 后 owner 字段会一直留着，直到租约过期才有别人接手；
     * 把"僵尸租约"当成"正在消费"会让运维误判。
     *
     * <p>用途：{@code CdcRuntimeView.anyInstanceConsuming}。它与
     * {@code running}（本实例是否在跑）语义不同，不能合并：
     * 多实例下常见"本实例 running=false 但别的实例正在消费"。
     *
     * @param taskId 任务 ID
     * @return true 表示至少有一个未过期租约持有者
     */
    public boolean anyLeaseActive(long taskId) {
        Long n = jdbc.queryForObject("SELECT COUNT(1) FROM sync_change_watermark WHERE task_id=? "
                        + "AND consumer_owner IS NOT NULL AND owner_expire_at IS NOT NULL "
                        + "AND owner_expire_at > ?",
                Long.class, taskId, toTimestamp(now()));
        return n != null && n.longValue() > 0L;
    }

    /**
     * 删除任务下全部水位。
     *
     * @param taskId 任务 ID
     * @return 影响行数
     */
    public int deleteByTask(long taskId) {
        return jdbc.update("DELETE FROM sync_change_watermark WHERE task_id=?", taskId);
    }
}
