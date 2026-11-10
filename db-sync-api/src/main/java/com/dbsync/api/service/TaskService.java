package com.dbsync.api.service;

import com.dbsync.api.cdc.CdcRuntimeManager;
import com.dbsync.api.dto.task.TaskRequest;
import com.dbsync.api.dto.task.TaskView;
import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.DataSourceDef;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.util.SnowflakeId;
import com.dbsync.metadata.dao.DataSourceDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务服务（PRD ORC-01，T43 任务 CRUD + 状态机）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>{@code group_count} 创建后<b>不可修改</b>（T43 验收 5：改则 E1001）。原因：
 *       它决定增量保序的分区数，改它会让已有 watermark 的 {@code group_no} 语义错位，
 *       直接破坏不变式 I4；</li>
 *   <li>源/目标数据源必须存在且不同；源库为 {@code PROD} 时目标库不能是 {@code DEV}
 *       （防止把生产数据同步进开发环境）；</li>
 *   <li>状态跃迁一律走 {@link TaskState#canTransitionTo}，非法跃迁抛 E3001。</li>
 * </ul>
 */
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final SyncTaskDao syncTaskDao;
    private final DataSourceDao dataSourceDao;
    private final TableMappingDao tableMappingDao;
    private final SnowflakeId idGenerator;
    private final CdcRuntimeManager cdcRuntime;

    /**
     * 构造。
     *
     * @param syncTaskDao     任务 DAO
     * @param dataSourceDao   数据源 DAO
     * @param tableMappingDao 表映射 DAO（删除前清理）
     * @param idGenerator     雪花 ID
     * @param cdcRuntime      增量消费运行期（任务离开 RUNNING/被删除时必须停掉会话）
     */
    public TaskService(SyncTaskDao syncTaskDao, DataSourceDao dataSourceDao,
                       TableMappingDao tableMappingDao, SnowflakeId idGenerator,
                       CdcRuntimeManager cdcRuntime) {
        this.syncTaskDao = syncTaskDao;
        this.dataSourceDao = dataSourceDao;
        this.tableMappingDao = tableMappingDao;
        this.idGenerator = idGenerator == null
                ? new SnowflakeId(SnowflakeId.defaultWorkerId()) : idGenerator;
        this.cdcRuntime = cdcRuntime;
    }

    /**
     * 新建任务。
     *
     * @param req      请求
     * @param operator 操作人
     * @return 视图
     */
    public TaskView create(TaskRequest req, String operator) {
        validate(req);
        long id = idGenerator.nextId();
        SyncTask task = new SyncTask(id, req.name().trim(), req.sourceDsId().longValue(),
                req.targetDsId().longValue(), req.groupCount() == null ? 16
                        : req.groupCount().intValue(),
                req.concurrency() == null ? 4 : req.concurrency().intValue(),
                req.batchSize() == null ? 1000 : req.batchSize().intValue(),
                req.shardRows() == null ? 2_000_000 : req.shardRows().intValue(),
                req.rateLimitRows() == null ? 0L : req.rateLimitRows().longValue(),
                req.maxRetry() == null ? 3 : req.maxRetry().intValue(),
                parseMode(req.incrementalMode()),
                req.consumerConcurrency() == null ? 8 : req.consumerConcurrency().intValue(),
                TaskState.DRAFT);
        syncTaskDao.insert(task, operator);
        log.info("任务已创建 id={} name={} by={}", id, task.getName(), operator);
        return TaskView.of(syncTaskDao.findById(id).orElse(task));
    }

    /**
     * 编辑任务（{@code groupCount} 改动将被拒绝）。
     *
     * @param id       任务 ID
     * @param req      请求
     * @param operator 操作人
     * @return 视图
     */
    public TaskView update(long id, TaskRequest req, String operator) {
        SyncTask existing = loadOrThrow(id);
        validate(req);
        if (req.groupCount() != null && req.groupCount().intValue() != existing.getGroupCount()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID,
                    "groupCount 创建后不可修改（影响增量保序分区）")
                    .with("current", Integer.valueOf(existing.getGroupCount()))
                    .with("requested", req.groupCount());
        }
        if (existing.getState().isTerminal()) {
            throw new DbSyncException(ErrorCode.TASK_STATE_INVALID,
                    "终态任务不可编辑：" + existing.getState());
        }
        int rows = syncTaskDao.updateConfig(id, req.name().trim(),
                req.concurrency() == null ? existing.getConcurrency() : req.concurrency().intValue(),
                req.batchSize() == null ? existing.getBatchSize() : req.batchSize().intValue(),
                req.shardRows() == null ? (int) existing.getShardRows() : req.shardRows().intValue(),
                req.rateLimitRows() == null ? existing.getRateLimitRows()
                        : req.rateLimitRows().longValue(),
                req.maxRetry() == null ? existing.getMaxRetry() : req.maxRetry().intValue(),
                req.consumerConcurrency() == null ? existing.getConsumerConcurrency()
                        : req.consumerConcurrency().intValue(),
                syncTaskDao.versionOf(id));
        if (rows == 0) {
            throw new DbSyncException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, "任务", id);
        }
        log.info("任务已更新 id={} by={}", id, operator);
        return TaskView.of(loadOrThrow(id));
    }

    /**
     * 详情。
     *
     * @param id 任务 ID
     * @return 视图
     */
    public TaskView get(long id) {
        return TaskView.of(loadOrThrow(id));
    }

    /**
     * 列表。
     *
     * @return 视图列表
     */
    public List<TaskView> list() {
        List<TaskView> views = new ArrayList<>();
        for (SyncTask task : syncTaskDao.findAll()) {
            views.add(TaskView.of(task));
        }
        return views;
    }

    /**
     * 删除任务（软删；有在跑分片则拒绝）。
     *
     * @param id       任务 ID
     * @param operator 操作人
     */
    public void delete(long id, String operator) {
        SyncTask task = loadOrThrow(id);
        if (task.getState() == TaskState.RUNNING) {
            throw new DbSyncException(ErrorCode.TASK_STATE_INVALID, "运行中的任务不可删除，请先取消");
        }
        tableMappingDao.deleteByTask(id);
        syncTaskDao.softDelete(id);
        // 任务被删了消费者必须停：否则线程还在按 taskId 读变更日志、往目标库写
        cdcRuntime.stop(id);
        log.info("任务已删除 id={} by={}", id, operator);
    }

    /**
     * 状态动作（READY / ARCHIVE）。
     *
     * @param id     任务 ID
     * @param action READY 或 ARCHIVE
     * @param reason 理由（ARCHIVE 必填）
     * @return 视图
     */
    public TaskView act(long id, String action, String reason) {
        SyncTask task = loadOrThrow(id);
        String normalized = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        TaskState target;
        switch (normalized) {
            case "READY":
                target = TaskState.READY;
                break;
            case "ARCHIVE":
            case "ARCHIVED":
                target = TaskState.ARCHIVED;
                if (reason == null || reason.isBlank()) {
                    throw new DbSyncException(ErrorCode.SECOND_CONFIRM_REQUIRED, "归档需填写理由");
                }
                break;
            default:
                throw new DbSyncException(ErrorCode.PARAM_INVALID, "不支持的动作：" + action);
        }
        transition(task, target);
        // READY / ARCHIVED 都不是"推进数据"的状态：增量会话必须停
        // （ARCHIVED 是终态；READY 说明任务被退回待启动，继续消费会让水位线继续前进）
        if (target != TaskState.RUNNING) {
            cdcRuntime.stop(id);
        }
        return TaskView.of(loadOrThrow(id));
    }

    /**
     * 通用状态跃迁（供迁移/增量服务复用）。
     *
     * @param task 任务（当前状态以库中为准）
     * @param to   目标状态
     */
    public void transition(SyncTask task, TaskState to) {
        TaskState from = syncTaskDao.stateOf(task.getId()).orElse(task.getState());
        if (from == to) {
            return;
        }
        if (!from.canTransitionTo(to)) {
            throw new DbSyncException(ErrorCode.TASK_STATE_INVALID,
                    from + " -> " + to).with("from", from.name()).with("to", to.name())
                    .with("allowed", from.allowedNext());
        }
        if (syncTaskDao.transition(task.getId(), from, to) == 0) {
            throw new DbSyncException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                    "任务状态已被并发修改：" + task.getId());
        }
    }

    /**
     * 读取任务。
     *
     * @param id 任务 ID
     * @return 任务
     */
    public SyncTask loadOrThrow(long id) {
        return syncTaskDao.findById(id).orElseThrow(
                () -> new DbSyncException(ErrorCode.TASK_NOT_FOUND, id));
    }

    private void validate(TaskRequest req) {
        if (req == null) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "请求体不能为空");
        }
        if (req.sourceDsId().longValue() == req.targetDsId().longValue()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "源数据源与目标数据源不能相同");
        }
        DataSourceDef source = dataSourceDao.findById(req.sourceDsId().longValue())
                .orElseThrow(() -> new DbSyncException(ErrorCode.DATASOURCE_NOT_FOUND,
                        req.sourceDsId()));
        DataSourceDef target = dataSourceDao.findById(req.targetDsId().longValue())
                .orElseThrow(() -> new DbSyncException(ErrorCode.DATASOURCE_NOT_FOUND,
                        req.targetDsId()));
        if ("PROD".equals(source.getEnv().name()) && "DEV".equals(target.getEnv().name())) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID,
                    "禁止把 PROD 源库同步到 DEV 目标库");
        }
        parseMode(req.incrementalMode());
    }

    private static IncrementalMode parseMode(String value) {
        if (value == null || value.isBlank()) {
            return IncrementalMode.TRIGGER_LOG;
        }
        try {
            return IncrementalMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "不支持的增量模式：" + value);
        }
    }
}
