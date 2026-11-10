package com.dbsync.metadata.dao;

import com.dbsync.common.enums.ConflictPolicy;
import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.model.SyncTask;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 同步任务 DAO。状态跃迁用"带期望状态的 UPDATE"实现乐观并发（跨实例也安全）。
 */
@Repository
public class SyncTaskDao extends AbstractMetaDao {

    private final RowMapper<SyncTask> mapper = new RowMapper<SyncTask>() {
        @Override
        public SyncTask mapRow(ResultSet rs, int rowNum) throws SQLException {
            SyncTask task = new SyncTask(rs.getLong("id"), rs.getString("name"),
                    rs.getLong("source_ds_id"), rs.getLong("target_ds_id"), rs.getInt("group_count"),
                    rs.getInt("concurrency"), rs.getInt("batch_size"), rs.getInt("shard_rows"),
                    rs.getLong("rate_limit_rows"), rs.getInt("max_retry"),
                    IncrementalMode.valueOf(rs.getString("incremental_mode")),
                    rs.getInt("consumer_concurrency"), TaskState.valueOf(rs.getString("state")));
            task.setLastError(rs.getString("last_error"));
            task.setStartedAt(instantOf(rs, "started_at"));
            task.setFinishedAt(instantOf(rs, "finished_at"));
            // 采集周期与任务级冲突策略：V1 建表就有列，此前模型层没读出来，
            // 导致"任务级配置"事实上不生效（采集只能写死周期、策略只能写死 SOURCE_WINS）。
            task.setPollIntervalMs(rs.getInt("poll_interval_ms"));
            // 列是 NOT NULL DEFAULT 'SOURCE_WINS'，空值只可能来自手工改库；
            // 此时用列默认值兜底（而不是对"任务级未配置"这种合法场景抛错），
            // 但非空且非法的值仍然抛 E1001。
            task.setConflictPolicy(ConflictPolicy.parseOrDefault(
                    rs.getString("conflict_policy"), ConflictPolicy.SOURCE_WINS));
            return task;
        }
    };

    /**
     * 构造。
     *
     * @param jdbc 元数据库 JdbcTemplate
     */
    public SyncTaskDao(@Qualifier("metaJdbcTemplate") JdbcTemplate jdbc) {
        super(jdbc);
    }

    /**
     * 新增任务。
     *
     * @param task 任务
     * @param by   创建人
     * @return 影响行数
     */
    public int insert(SyncTask task, String by) {
        Instant now = now();
        return jdbc.update("INSERT INTO sync_task (id,name,source_ds_id,target_ds_id,state,phase,"
                        + "concurrency,batch_size,shard_rows,rate_limit_rows,max_retry,incremental_mode,"
                        + "group_count,consumer_concurrency,version,created_by,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,?)",
                task.getId(), task.getName(), task.getSourceDsId(), task.getTargetDsId(),
                task.getState().name(), "MIGRATION", task.getConcurrency(), task.getBatchSize(),
                task.getShardRows(), task.getRateLimitRows(), task.getMaxRetry(),
                task.getIncrementalMode().name(), task.getGroupCount(),
                task.getConsumerConcurrency(), by, toTimestamp(now), toTimestamp(now));
    }

    /**
     * 按 ID 查询。
     *
     * @param id 任务 ID
     * @return 任务
     */
    public Optional<SyncTask> findById(long id) {
        List<SyncTask> list = jdbc.query(
                "SELECT * FROM sync_task WHERE id=? AND deleted_at IS NULL", mapper, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 列表。
     *
     * @return 未删除任务
     */
    public List<SyncTask> findAll() {
        return jdbc.query("SELECT * FROM sync_task WHERE deleted_at IS NULL ORDER BY id", mapper);
    }

    /**
     * 按状态列表。
     *
     * @param state 状态
     * @return 任务列表
     */
    public List<SyncTask> findByState(TaskState state) {
        return jdbc.query("SELECT * FROM sync_task WHERE state=? AND deleted_at IS NULL", mapper,
                state.name());
    }

    /**
     * 条件状态跃迁（乐观并发：只有当前状态匹配才更新）。
     *
     * @param id   任务 ID
     * @param from 期望当前状态
     * @param to   目标状态
     * @return 影响行数（0 表示状态已被他人改变）
     */
    public int transition(long id, TaskState from, TaskState to) {
        Instant now = now();
        String extra = to == TaskState.RUNNING ? ",started_at=COALESCE(started_at,?)"
                : (to == TaskState.FINISHED ? ",finished_at=?" : "");
        if (extra.isEmpty()) {
            return jdbc.update("UPDATE sync_task SET state=?,version=version+1,updated_at=? "
                    + "WHERE id=? AND state=?", to.name(), toTimestamp(now), id, from.name());
        }
        return jdbc.update("UPDATE sync_task SET state=?,version=version+1,updated_at=?" + extra
                + " WHERE id=? AND state=?", to.name(), toTimestamp(now), toTimestamp(now), id,
                from.name());
    }

    /**
     * 记录错误并置失败。
     *
     * @param id    任务 ID
     * @param error 错误信息
     * @return 影响行数
     */
    public int markFailed(long id, String error) {
        return jdbc.update("UPDATE sync_task SET state=?,last_error=?,version=version+1,"
                + "updated_at=? WHERE id=?", TaskState.FAILED.name(), error,
                toTimestamp(now()), id);
    }

    /**
     * 更新可编辑配置（乐观锁）。
     *
     * <p>// ADDED: 04 文档 T43 要求任务可编辑（{@code PUT /api/tasks/{id}}），
     * 但原有 DAO 只有状态跃迁/软删，无法改 name/concurrency 等业务字段。
     * <b>注意</b>：{@code group_count} 不在此列——它决定增量保序分区，
     * 一旦有数据就不可变更（改它会打乱 watermark 与 partition 映射）。
     *
     * @param id          任务 ID
     * @param name        名称
     * @param concurrency 并发
     * @param batchSize   批大小
     * @param shardRows   每分片行数
     * @param rateLimit   限速
     * @param maxRetry    最大重试
     * @param consumerConcurrency 消费并发
     * @param version     期望版本
     * @return 影响行数（0 表示版本冲突）
     */
    public int updateConfig(long id, String name, int concurrency, int batchSize, int shardRows,
                            long rateLimit, int maxRetry, int consumerConcurrency, int version) {
        return jdbc.update("UPDATE sync_task SET name=?,concurrency=?,batch_size=?,shard_rows=?,"
                + "rate_limit_rows=?,max_retry=?,consumer_concurrency=?,version=version+1,"
                + "updated_at=? WHERE id=? AND version=?",
                name, concurrency, batchSize, shardRows, rateLimit, maxRetry, consumerConcurrency,
                toTimestamp(now()), id, version);
    }

    /**
     * 读取乐观锁版本号。
     *
     * <p>// ADDED: {@link com.dbsync.common.model.SyncTask} 不携带 version 字段。
     *
     * @param id 任务 ID
     * @return 版本号；不存在返回 -1
     */
    public int versionOf(long id) {
        List<Integer> list = jdbc.queryForList(
                "SELECT version FROM sync_task WHERE id=? AND deleted_at IS NULL",
                Integer.class, id);
        return list.isEmpty() || list.get(0) == null ? -1 : list.get(0).intValue();
    }

    /**
     * 切换增量模式。
     *
     * <p>// ADDED: 04 文档 T32 要求 {@code PUT /cdc/mode}，原有 DAO 无此更新方法。
     *
     * @param id   任务 ID
     * @param mode 目标模式
     * @return 影响行数
     */
    public int updateIncrementalMode(long id, com.dbsync.common.enums.IncrementalMode mode) {
        return jdbc.update("UPDATE sync_task SET incremental_mode=?,version=version+1,"
                + "updated_at=? WHERE id=?", mode.name(), toTimestamp(now()), id);
    }

    /**
     * 软删除。
     *
     * @param id 任务 ID
     * @return 影响行数
     */
    public int softDelete(long id) {
        return jdbc.update("UPDATE sync_task SET deleted_at=?,updated_at=? WHERE id=?",
                toTimestamp(now()), toTimestamp(now()), id);
    }

    /**
     * 只更新限速（MIG-05 运行中动态限速的"持久化"一半）。
     *
     * <p>// ADDED: 原有 {@link #updateConfig} 要求同时带上 name/concurrency/... 与
     * version（整表单改），改限速时用它会把用户未提交的其它字段一并覆盖，
     * 也要求调用方先读 version（并发下易冲突）。限速是"单字段、幂等"操作，
     * 单独一条 UPDATE 更安全。
     *
     * <p>不驱动状态机、不动 version：限速与分片 CAS 无关，改它不应让
     * 正在抢占分片的实例出现版本冲突。
     *
     * @param id            任务 ID
     * @param rateLimitRows 限速（行/秒，0 表示不限速）
     * @return 影响行数（0 表示任务不存在或已软删）
     */
    public int updateRateLimit(long id, long rateLimitRows) {
        return jdbc.update("UPDATE sync_task SET rate_limit_rows=?,updated_at=? "
                + "WHERE id=? AND deleted_at IS NULL", Long.valueOf(rateLimitRows),
                toTimestamp(now()), id);
    }

    /**
     * 读任务当前状态（跨实例取消检测用，复用 assertHeld 的时机）。
     *
     * @param id 任务 ID
     * @return 状态；任务不存在返回 empty
     */
    public Optional<TaskState> stateOf(long id) {
        List<String> list = jdbc.queryForList("SELECT state FROM sync_task WHERE id=?",
                String.class, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(TaskState.valueOf(list.get(0)));
    }
}
