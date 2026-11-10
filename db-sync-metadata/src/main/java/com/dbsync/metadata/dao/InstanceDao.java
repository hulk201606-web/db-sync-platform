package com.dbsync.metadata.dao;

import com.dbsync.common.model.InstanceInfo;
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
 * 实例心跳 DAO（多实例部署的可观测性底座）。
 *
 * <p>用途：
 * <ul>
 *   <li>运维大盘展示"当前有几个实例在跑、各自持多少分片"；</li>
 *   <li>识别僵尸实例（心跳超时但租约还没过期），用于提前告警而不必等 30s 租约；</li>
 *   <li>滚动升级时判断"旧实例是否已全部下线"。</li>
 * </ul>
 */
@Repository
public class InstanceDao extends AbstractMetaDao {

    /**
     * 全平台统一的"实例存活判定窗口"（90s）。
     *
     * <p>原实现有三处各自写死：{@code HeartbeatJob.ALIVE_WINDOW=30s}（死常量、无人引用）、
     * {@code MonitorService.ALIVE_WINDOW=90s}、本类 {@code countAlive/listAlive} 的 null 兜底 30s。
     * 这里收敛为<b>唯一真源</b>，api 层（{@code MonitorService}）直接引用本常量，
     * 未显式传窗口时 DAO 也用它兜底，避免口径分裂。
     */
    public static final Duration DEFAULT_ALIVE_WINDOW = Duration.ofSeconds(90L);

    private final RowMapper<InstanceInfo> mapper = new RowMapper<InstanceInfo>() {
        @Override
        public InstanceInfo mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new InstanceInfo(rs.getString("id"), rs.getString("host"), rs.getInt("port"),
                    rs.getString("version"), rs.getString("state"),
                    instantOf(rs, "started_at"), instantOf(rs, "heartbeat_at"),
                    rs.getInt("shard_count"));
        }
    };

    /**
     * 构造。
     *
     * @param jdbc 元数据库 JdbcTemplate
     */
    public InstanceDao(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc) {
        super(jdbc);
    }

    /**
     * 注册（幂等：已存在则刷新心跳）。
     *
     * @param info 实例信息
     * @return 影响行数
     */
    public int register(InstanceInfo info) {
        Instant now = now();
        int rows = jdbc.update("UPDATE sys_instance SET host=?,port=?,version=?,state=?,"
                        + "heartbeat_at=?,shard_count=? WHERE id=?",
                info.getHost(), info.getPort(), info.getVersion(), "UP", toTimestamp(now),
                info.getShardCount(), info.getId());
        if (rows > 0) {
            return rows;
        }
        try {
            return jdbc.update("INSERT INTO sys_instance (id,host,port,version,state,started_at,"
                            + "heartbeat_at,shard_count,task_count) VALUES (?,?,?,?,?,?,?,?,0)",
                    info.getId(), info.getHost(), info.getPort(), info.getVersion(), "UP",
                    toTimestamp(info.getStartedAt() == null ? now : info.getStartedAt()),
                    toTimestamp(now), info.getShardCount());
        } catch (DuplicateKeyException race) {
            return jdbc.update("UPDATE sys_instance SET heartbeat_at=? WHERE id=?",
                    toTimestamp(now), info.getId());
        }
    }

    /**
     * 心跳（每 10s 调用一次）。
     *
     * @param instanceId 实例 ID
     * @param shardCount 当前持有分片数
     * @param taskCount  当前执行任务数
     * @return 影响行数（0 表示台账被清理，调用方应重新 register）
     */
    public int heartbeat(String instanceId, int shardCount, int taskCount) {
        return jdbc.update("UPDATE sys_instance SET heartbeat_at=?,shard_count=?,task_count=? "
                        + "WHERE id=?",
                toTimestamp(now()), shardCount, taskCount, instanceId);
    }

    /**
     * 标记状态（UP / DRAINING / DOWN）。
     *
     * @param instanceId 实例 ID
     * @param state      状态
     * @return 影响行数
     */
    public int updateState(String instanceId, String state) {
        return jdbc.update("UPDATE sys_instance SET state=?,heartbeat_at=? WHERE id=?",
                state, toTimestamp(now()), instanceId);
    }

    /**
     * 注销（优雅停机）。
     *
     * @param instanceId 实例 ID
     * @return 影响行数
     */
    public int deregister(String instanceId) {
        return jdbc.update("DELETE FROM sys_instance WHERE id=?", instanceId);
    }

    /**
     * 按 ID 查询。
     *
     * @param instanceId 实例 ID
     * @return 实例信息
     */
    public Optional<InstanceInfo> findById(String instanceId) {
        List<InstanceInfo> list = jdbc.query("SELECT * FROM sys_instance WHERE id=?",
                mapper, instanceId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 列出活跃实例（心跳在给定窗口内）。
     *
     * @param aliveWindow 活跃窗口（null 时用统一默认值 {@link #DEFAULT_ALIVE_WINDOW} = 90s）
     * @return 实例列表
     */
    public List<InstanceInfo> listAlive(Duration aliveWindow) {
        Instant threshold = now().minus(aliveWindow == null ? DEFAULT_ALIVE_WINDOW : aliveWindow);
        return jdbc.query("SELECT * FROM sys_instance WHERE heartbeat_at >= ? AND state<>? "
                + "ORDER BY id", mapper, toTimestamp(threshold), "DOWN");
    }

    /**
     * 列出全部实例。
     *
     * @return 实例列表
     */
    public List<InstanceInfo> listAll() {
        return jdbc.query("SELECT * FROM sys_instance ORDER BY id", mapper);
    }

    /**
     * 清理僵尸实例台账（心跳超时的行）。
     *
     * <p>注意：这里的 150s 兜底是<b>僵尸清理窗口</b>（≈ 5 × 心跳间隔），与
     * {@link #DEFAULT_ALIVE_WINDOW} 的"存活判定窗口"是两条独立规则，不强行统一。
     *
     * @param staleWindow 判定僵尸的窗口（建议 5 × 心跳间隔）
     * @return 清理行数
     */
    public int removeStale(Duration staleWindow) {
        Instant threshold = now().minus(staleWindow == null ? Duration.ofSeconds(150) : staleWindow);
        return jdbc.update("DELETE FROM sys_instance WHERE heartbeat_at < ?",
                toTimestamp(threshold));
    }

    /**
     * 统计活跃实例数。
     *
     * @param aliveWindow 活跃窗口（null 时用统一默认值 {@link #DEFAULT_ALIVE_WINDOW} = 90s）
     * @return 数量
     */
    public long countAlive(Duration aliveWindow) {
        Instant threshold = now().minus(aliveWindow == null ? DEFAULT_ALIVE_WINDOW : aliveWindow);
        Long n = jdbc.queryForObject("SELECT COUNT(1) FROM sys_instance WHERE heartbeat_at >= ? "
                + "AND state<>?", Long.class, toTimestamp(threshold), "DOWN");
        return n == null ? 0L : n;
    }
}
