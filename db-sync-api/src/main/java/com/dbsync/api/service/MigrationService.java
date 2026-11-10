package com.dbsync.api.service;

import com.dbsync.api.cdc.CdcRuntimeManager;
import com.dbsync.api.dto.migration.MigrationActionView;
import com.dbsync.api.dto.migration.MigrationProgressView;
import com.dbsync.api.dto.migration.PrecheckView;
import com.dbsync.api.dto.migration.RowCountVerifyView;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.model.SyncTask;
import com.dbsync.migration.MigrationEngine;
import com.dbsync.migration.MigrationPrecheck;
import com.dbsync.migration.MigrationProgress;
import com.dbsync.migration.RowCountVerifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 迁移服务（T27：预检/启动/暂停/恢复/取消/进度/核数）。
 *
 * <p>两条硬要求：
 * <ul>
 *   <li><b>幂等</b>（T27 验收 1）：{@code pause} 在已是 PAUSED、{@code resume} 在已是 RUNNING 时
 *       不再调用引擎（引擎会抛 E3001），直接返回 {@code applied=false}；</li>
 *   <li><b>进度读缓存</b>（T27 验收 2）：进度查询会聚合 {@code sync_shard} 状态，
 *       并发大盘轮询下会打爆元数据库，因此加 1 秒 TTL 的内存缓存。
 *       {@code // ADDED: 04 文档要求"读缓存"，但未规定实现位置，放在服务层最简单}。</li>
 *   <li><b>增量消费跟着状态走</b>（CDC-02）：{@code start/resume} 成功且库里已是
 *       {@code RUNNING} 时拉起消费，{@code pause/cancel} 后立即停止会话。
 *       没有这一处接线，增量变更只会堆在 {@code sync_change_log} 里永不落目标库。</li>
 * </ul>
 */
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);

    /** 进度缓存 TTL（毫秒）。 */
    private static final long PROGRESS_TTL_MS = 1000L;

    private final MigrationEngine migrationEngine;
    private final MigrationPrecheck precheck;
    private final RowCountVerifier rowCountVerifier;
    private final TaskService taskService;
    private final CdcRuntimeManager cdcRuntime;
    private final Map<Long, CachedProgress> progressCache = new ConcurrentHashMap<>();

    /**
     * 构造。
     *
     * @param migrationEngine 迁移引擎
     * @param precheck        预检器
     * @param rowCountVerifier 核数器
     * @param taskService     任务服务（读状态）
     * @param cdcRuntime      增量消费运行期（任务进入 RUNNING 起增量、离开时停）
     */
    public MigrationService(MigrationEngine migrationEngine, MigrationPrecheck precheck,
                            RowCountVerifier rowCountVerifier, TaskService taskService,
                            CdcRuntimeManager cdcRuntime) {
        this.migrationEngine = migrationEngine;
        this.precheck = precheck;
        this.rowCountVerifier = rowCountVerifier;
        this.taskService = taskService;
        this.cdcRuntime = cdcRuntime;
    }

    /**
     * 预检。
     *
     * @param taskId 任务 ID
     * @return 预检结果
     */
    public PrecheckView precheck(long taskId) {
        taskService.loadOrThrow(taskId);
        return PrecheckView.of(precheck.run(taskId));
    }

    /**
     * 启动迁移。
     *
     * @param taskId 任务 ID
     * @return 动作结果
     */
    public MigrationActionView start(long taskId) {
        SyncTask task = taskService.loadOrThrow(taskId);
        if (task.getState() == TaskState.RUNNING && migrationEngine.isRunning(taskId)) {
            return new MigrationActionView(taskId, "START", task.getState().name(), false);
        }
        migrationEngine.start(taskId);
        // 任务已在库里置为 RUNNING：增量消费从此刻开始（存量/增量并行，架构 §4.3 的双写阶段）
        startConsumeIfRunning(taskId);
        invalidate(taskId);
        return new MigrationActionView(taskId, "START",
                taskService.loadOrThrow(taskId).getState().name(), true);
    }

    /**
     * 暂停（幂等）。
     *
     * @param taskId 任务 ID
     * @return 动作结果
     */
    public MigrationActionView pause(long taskId) {
        SyncTask task = taskService.loadOrThrow(taskId);
        if (task.getState() == TaskState.PAUSED) {
            return new MigrationActionView(taskId, "PAUSE", task.getState().name(), false);
        }
        migrationEngine.pause(taskId);
        // 暂停后不再消费：变更继续由触发器写入 sync_change_log，恢复时从水位线续跑
        cdcRuntime.stop(taskId);
        invalidate(taskId);
        return new MigrationActionView(taskId, "PAUSE",
                taskService.loadOrThrow(taskId).getState().name(), true);
    }

    /**
     * 恢复（幂等）。
     *
     * @param taskId 任务 ID
     * @return 动作结果
     */
    public MigrationActionView resume(long taskId) {
        SyncTask task = taskService.loadOrThrow(taskId);
        if (task.getState() == TaskState.RUNNING) {
            return new MigrationActionView(taskId, "RESUME", task.getState().name(), false);
        }
        migrationEngine.resume(taskId);
        startConsumeIfRunning(taskId);
        invalidate(taskId);
        return new MigrationActionView(taskId, "RESUME",
                taskService.loadOrThrow(taskId).getState().name(), true);
    }

    /**
     * 取消（幂等：已 FAILED 视为已取消）。
     *
     * @param taskId 任务 ID
     * @return 动作结果
     */
    public MigrationActionView cancel(long taskId) {
        SyncTask task = taskService.loadOrThrow(taskId);
        if (task.getState() == TaskState.FAILED) {
            return new MigrationActionView(taskId, "CANCEL", task.getState().name(), false);
        }
        migrationEngine.cancel(taskId);
        // 取消后任务置 FAILED（或已是终态）：会话必须停，否则会继续往目标库写
        cdcRuntime.stop(taskId);
        invalidate(taskId);
        return new MigrationActionView(taskId, "CANCEL",
                taskService.loadOrThrow(taskId).getState().name(), true);
    }

    /**
     * 进度（1 秒缓存）。
     *
     * @param taskId 任务 ID
     * @return 进度
     */
    public MigrationProgressView progress(long taskId) {
        long now = System.currentTimeMillis();
        CachedProgress cached = progressCache.get(Long.valueOf(taskId));
        if (cached != null && now - cached.loadedAt < PROGRESS_TTL_MS) {
            return cached.view;
        }
        MigrationProgress progress = migrationEngine.progress(taskId);
        MigrationProgressView view = MigrationProgressView.of(progress);
        // 结束态不再缓存，避免"跑完还显示旧进度"
        if (view.finished()) {
            progressCache.remove(Long.valueOf(taskId));
        } else {
            progressCache.put(Long.valueOf(taskId), new CachedProgress(view, now));
        }
        return view;
    }

    /**
     * 核数。
     *
     * @param taskId 任务 ID
     * @return 核数结果
     */
    public RowCountVerifyView verify(long taskId) {
        taskService.loadOrThrow(taskId);
        return RowCountVerifyView.of(taskId, rowCountVerifier.verify(taskId));
    }

    /**
     * 清理进度缓存（任务被外部改变时调用）。
     *
     * @param taskId 任务 ID
     */
    public void invalidate(long taskId) {
        progressCache.remove(Long.valueOf(taskId));
    }

    /**
     * 引擎是否在本实例运行（供监控用）。
     *
     * @param taskId 任务 ID
     * @return true 表示在跑
     */
    public boolean isRunning(long taskId) {
        try {
            return migrationEngine.isRunning(taskId);
        } catch (RuntimeException e) {
            log.debug("查询运行状态失败 task={} err={}", taskId, e.toString());
            return false;
        }
    }

    /**
     * 任务确实进入 {@code RUNNING} 时拉起增量消费。
     *
     * <p>判断的是<b>库里的状态</b>而不是入参：引擎可能因为乐观锁冲突/并发被别的实例改状态，
     * 以库为准才不会出现"接口说启动了，实际没跑，但消费者已经开跑"。
     *
     * @param taskId 任务 ID
     */
    private void startConsumeIfRunning(long taskId) {
        SyncTask task;
        try {
            task = taskService.loadOrThrow(taskId);
        } catch (RuntimeException e) {
            log.warn("读取任务状态失败，跳过增量消费启动 task={} err={}", taskId, e.toString());
            return;
        }
        if (task.getState() != TaskState.RUNNING) {
            log.debug("任务未处于 RUNNING，不启动增量消费 task={} state={}", taskId, task.getState());
            return;
        }
        if (!CdcRuntimeManager.needsConsumer(task.getIncrementalMode())) {
            log.info("任务增量模式无需消费者，跳过 task={} mode={}", taskId,
                    task.getIncrementalMode());
            return;
        }
        cdcRuntime.start(taskId);
    }

    /** 进度缓存条目。 */
    private static final class CachedProgress {
        private final MigrationProgressView view;
        private final long loadedAt;

        private CachedProgress(MigrationProgressView view, long loadedAt) {
            this.view = view;
            this.loadedAt = loadedAt;
        }
    }
}
