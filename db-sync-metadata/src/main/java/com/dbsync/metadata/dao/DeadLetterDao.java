package com.dbsync.metadata.dao;

import com.dbsync.common.enums.DeadLetterSourceType;
import com.dbsync.common.enums.DeadLetterStatus;
import com.dbsync.common.model.DeadLetter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 死信 DAO。
 *
 * <p>Gate 指标 {@code OPEN_DEAD_LETTERS == 0} 直接读 {@link #countOpen(long)}，
 * 因此本类不能用"近似值"（例如带缓存的计数），必须实时查库。
 */
@Repository
public class DeadLetterDao extends AbstractMetaDao {

    private final RowMapper<DeadLetter> mapper = new RowMapper<DeadLetter>() {
        @Override
        public DeadLetter mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DeadLetter(rs.getLong("id"), rs.getLong("task_id"),
                    nullableLong(rs, "table_mapping_id"),
                    DeadLetterSourceType.valueOf(rs.getString("source_type")),
                    nullableLong(rs, "ref_id"), rs.getString("pk_json"),
                    rs.getString("payload_json"), rs.getString("error_code"),
                    rs.getString("error_msg"), rs.getString("stack_digest"),
                    DeadLetterStatus.valueOf(rs.getString("status")), rs.getInt("retry"));
        }
    };

    /**
     * 构造。
     *
     * @param jdbc 元数据库 JdbcTemplate
     */
    public DeadLetterDao(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc) {
        super(jdbc);
    }

    /**
     * 新增死信。
     *
     * @param letter 死信
     * @return 影响行数
     */
    public int insert(DeadLetter letter) {
        java.time.Instant now = now();
        return jdbc.update("INSERT INTO sync_dead_letter (id,task_id,table_mapping_id,source_type,"
                        + "ref_id,pk_json,payload_json,error_code,error_msg,stack_digest,retry,"
                        + "status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                letter.getId(), letter.getTaskId(), letter.getTableMappingId(),
                letter.getSourceType().name(), letter.getRefId(), letter.getPkJson(),
                letter.getPayloadJson(), letter.getErrorCode(),
                truncate(letter.getErrorMsg(), 2000), truncate(letter.getStackDigest(), 1000),
                letter.getRetry(), letter.getStatus().name(), toTimestamp(now),
                toTimestamp(now));
    }

    /**
     * 按 ID 查询。
     *
     * @param id 死信 ID
     * @return 死信
     */
    public Optional<DeadLetter> findById(long id) {
        List<DeadLetter> list = jdbc.query("SELECT * FROM sync_dead_letter WHERE id=?", mapper, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 列出任务死信（可按状态过滤，status 为 null 表示不过滤）。
     *
     * @param taskId 任务 ID
     * @param status 状态（可 null）
     * @param limit  上限
     * @return 死信列表（按创建时间倒序）
     */
    public List<DeadLetter> list(long taskId, DeadLetterStatus status, int limit) {
        int effective = limit <= 0 ? 100 : limit;
        if (status == null) {
            return jdbc.query("SELECT * FROM sync_dead_letter WHERE task_id=? "
                            + "ORDER BY created_at DESC LIMIT " + effective,
                    mapper, taskId);
        }
        return jdbc.query("SELECT * FROM sync_dead_letter WHERE task_id=? AND status=? "
                        + "ORDER BY created_at DESC LIMIT " + effective,
                mapper, taskId, status.name());
    }

    /**
     * 统计 OPEN 死信数（Gate 判定）。
     *
     * @param taskId 任务 ID
     * @return 数量
     */
    public long countOpen(long taskId) {
        Long n = jdbc.queryForObject("SELECT COUNT(1) FROM sync_dead_letter WHERE task_id=? "
                + "AND status=?", Long.class, taskId, DeadLetterStatus.OPEN.name());
        return n == null ? 0L : n;
    }

    /**
     * 全局 OPEN 死信数（运维大盘用）。
     *
     * @return 数量
     */
    public long countOpenAll() {
        Long n = jdbc.queryForObject("SELECT COUNT(1) FROM sync_dead_letter WHERE status=?",
                Long.class, DeadLetterStatus.OPEN.name());
        return n == null ? 0L : n;
    }

    /**
     * 按状态统计。
     *
     * @param taskId 任务 ID
     * @param status 状态
     * @return 数量
     */
    public long countByStatus(long taskId, DeadLetterStatus status) {
        Long n = jdbc.queryForObject("SELECT COUNT(1) FROM sync_dead_letter WHERE task_id=? "
                + "AND status=?", Long.class, taskId, status.name());
        return n == null ? 0L : n;
    }

    /**
     * 置为重放中（防重复点击重放）。
     *
     * @param id      死信 ID
     * @param replayBy 操作人
     * @return 影响行数（0 表示已被他人重放）
     */
    public int markReplaying(long id, String replayBy) {
        return jdbc.update("UPDATE sync_dead_letter SET status=?,replay_by=?,replay_at=?,"
                        + "updated_at=? WHERE id=? AND status=?",
                DeadLetterStatus.REPLAYING.name(), replayBy, toTimestamp(now()),
                toTimestamp(now()), id, DeadLetterStatus.OPEN.name());
    }

    /**
     * 置为已重放。
     *
     * @param id 死信 ID
     * @return 影响行数
     */
    public int markReplayed(long id) {
        return jdbc.update("UPDATE sync_dead_letter SET status=?,retry=retry+1,updated_at=? "
                        + "WHERE id=?",
                DeadLetterStatus.REPLAYED.name(), toTimestamp(now()), id);
    }

    /**
     * 置为已丢弃（必须给理由，架构 §8.4）。
     *
     * @param id     死信 ID
     * @param reason 丢弃理由
     * @param by     操作人
     * @return 影响行数
     */
    public int markDiscarded(long id, String reason, String by) {
        return jdbc.update("UPDATE sync_dead_letter SET status=?,discard_reason=?,replay_by=?,"
                        + "replay_at=?,updated_at=? WHERE id=?",
                DeadLetterStatus.DISCARDED.name(), truncate(reason, 512), by,
                toTimestamp(now()), toTimestamp(now()), id);
    }

    /**
     * 重放失败：退回 OPEN 并累加重试次数。
     *
     * @param id       死信 ID
     * @param errorMsg 失败原因
     * @return 影响行数
     */
    public int reopen(long id, String errorMsg) {
        return jdbc.update("UPDATE sync_dead_letter SET status=?,retry=retry+1,error_msg=?,"
                        + "updated_at=? WHERE id=?",
                DeadLetterStatus.OPEN.name(), truncate(errorMsg, 2000), toTimestamp(now()), id);
    }

    /**
     * 删除任务下全部死信。
     *
     * @param taskId 任务 ID
     * @return 影响行数
     */
    public int deleteByTask(long taskId) {
        return jdbc.update("DELETE FROM sync_dead_letter WHERE task_id=?", taskId);
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
