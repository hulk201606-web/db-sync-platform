package com.dbsync.migration;

import com.dbsync.common.enums.ShardState;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.PrecheckReport;
import com.dbsync.common.model.Shard;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import com.dbsync.core.lease.ShardLeaseManager;
import com.dbsync.core.pool.NamedThreadPoolManager;
import com.dbsync.core.shard.ShardPlanner;
import com.dbsync.core.throttle.ThrottleRegistry;
import com.dbsync.metadata.dao.ShardDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 迁移引擎（存量迁移的门面 + 任务状态机 + 调度线程宿主）。
 *
 * <p><b>状态机</b>（架构 §9.1）：{@code READY → RUNNING ⇄ PAUSED → FINISHED/FAILED}。
 * 所有跃迁都必须经过 {@link SyncTaskDao#transition(long, TaskState, TaskState)}
 * 的 CAS，禁止直接 {@code UPDATE sync_task SET state=...}——
 * 否则两个实例同时点"启动"会各起一个 dispatcher，同一分片被两个线程搬。
 *
 * <p><b>三个易混淆操作的差别</b>：
 * <table border="1">
 *   <caption>pause / cancel / recover</caption>
 *   <tr><th>操作</th><th>做什么</th><th>分片状态</th><th>断点</th></tr>
 *   <tr><td>pause</td><td>停抢新分片，等在跑的分片收手</td><td>在跑的置回 PENDING</td><td>保留</td></tr>
 *   <tr><td>resume</td><td>把 PENDING/FAILED/PAUSED 重新纳入调度</td><td>→ PENDING</td><td>保留（继续跑）</td></tr>
 *   <tr><td>recover</td><td>实例启动时接管遗留 RUNNING 任务</td><td><b>不重建</b>，只回收过期租约</td><td>保留</td></tr>
 *   <tr><td>cancel</td><td>停止 + 任务置 FAILED（可修复后重跑）</td><td>在跑的置回 PENDING</td><td>保留（供重跑）</td></tr>
 * </table>
 */
@Service
public class MigrationEngine {

    private static final Logger log = LoggerFactory.getLogger(MigrationEngine.class);

    /** 暂停/取消后等待在跑分片收手的上限（PRD ORC-01：≤10s）。 */
    private static final Duration QUIESCE_TIMEOUT = Duration.ofSeconds(10L);

    private final SyncTaskDao syncTaskDao;
    private final ShardDao shardDao;
    private final TableMappingDao tableMappingDao;
    private final ShardPlanner shardPlanner;
    private final MigrationPrecheck precheck;
    private final TaskRuntimeFactory runtimeFactory;
    private final ShardLeaseManager leaseManager;
    private final NamedThreadPoolManager pools;
    private final ThrottleRegistry throttleRegistry;
    private final String instanceId;

    /** 本实例内的任务会话（跨实例靠元数据库租约，不靠这个 map）。 */
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();

    /**
     * 构造。
     *
     * @param syncTaskDao     任务 DAO
     * @param shardDao        分片 DAO
     * @param tableMappingDao 表映射 DAO
     * @param shardPlanner    分片规划器
     * @param precheck        预检（可为 null 表示跳过，仅限单测/受控场景）
     * @param runtimeFactory  运行时工厂
     * @param leaseManager    租约管理器
     * @param pools           线程池管理器
     * @param throttleRegistry 限速器注册表（任务结束时负责注销，防止内存泄漏）
     * @param instanceId      本实例 ID
     */
    public MigrationEngine(SyncTaskDao syncTaskDao, ShardDao shardDao,
                           TableMappingDao tableMappingDao, ShardPlanner shardPlanner,
                           MigrationPrecheck precheck, TaskRuntimeFactory runtimeFactory,
                           ShardLeaseManager leaseManager, NamedThreadPoolManager pools,
                           ThrottleRegistry throttleRegistry, String instanceId) {
        this.syncTaskDao = syncTaskDao;
        this.shardDao = shardDao;
        this.tableMappingDao = tableMappingDao;
        this.shardPlanner = shardPlanner;
        this.precheck = precheck;
        this.runtimeFactory = runtimeFactory;
        this.leaseManager = leaseManager;
        this.pools = pools;
        this.throttleRegistry = throttleRegistry == null
                ? new ThrottleRegistry() : throttleRegistry;
        this.instanceId = instanceId;
    }

    /**
     * 启动迁移：预检 → 规划分片（若未规划）→ READY→RUNNING → 起调度线程。
     *
     * @param taskId 任务 ID
     */
    public void start(long taskId) {
        SyncTask task = requireTask(taskId);
        if (task.getState() == TaskState.RUNNING && isRunning(taskId)) {
            log.info("任务已在本实例运行，忽略重复启动 task={}", taskId);
            return;
        }
        if (task.getState() != TaskState.READY) {
            throw new DbSyncException(ErrorCode.TASK_STATE_INVALID,
                    "只有 READY 状态的任务可以启动，当前为 " + task.getState() + "（taskId="
                            + taskId + "）");
        }
        if (precheck != null) {
            PrecheckReport report = precheck.run(taskId);
            if (report.hasBlocking()) {
                throw new DbSyncException(ErrorCode.PRECHECK_FAILED,
                        "预检未通过（" + report.getBlocking().size() + " 项）："
                                + String.join("；", report.getBlocking()))
                        .with("taskId", taskId);
            }
        }
        Session session = new Session();
        TaskRuntime runtime = runtimeFactory.create(taskId, session.cancel::get);

        // 分片只规划一次：已有分片说明上次跑过（或规划过），绝不能重规划
        List<Shard> existing = shardDao.findByTask(taskId);
        if (existing.isEmpty()) {
            List<Shard> planned = runtime.planShards(shardPlanner, null);
            if (planned.isEmpty()) {
                throw new DbSyncException(ErrorCode.SHARD_RANGE_INVALID,
                        "分片规划结果为空（taskId=" + taskId + "）");
            }
            shardDao.insertBatch(planned);
            log.info("分片规划完成 task={} shards={}", taskId, planned.size());
        } else {
            log.info("任务已有分片，跳过规划 task={} shards={}", taskId, existing.size());
        }

        if (syncTaskDao.transition(taskId, TaskState.READY, TaskState.RUNNING) == 0) {
            throw new DbSyncException(ErrorCode.TASK_STATE_INVALID,
                    "任务状态已被其他实例改变，启动失败（taskId=" + taskId + "）");
        }
        launch(taskId, task, runtime, session);
    }

    /**
     * 暂停：停止提交新批次，≤10s 生效（PRD ORC-01）。
     *
     * @param taskId 任务 ID
     */
    public void pause(long taskId) {
        SyncTask task = requireTask(taskId);
        if (task.getState() != TaskState.RUNNING) {
            throw new DbSyncException(ErrorCode.TASK_STATE_INVALID,
                    "只有 RUNNING 状态的任务可以暂停，当前为 " + task.getState());
        }
        Session session = sessions.get(taskId);
        if (session != null) {
            // ① 同实例立即生效：worker 每批检查一次这个标志
            session.cancel.set(true);
            // ② 跨实例生效靠 sync_task.state=PAUSED（worker 每 5 批查一次，见 ShardWorker）
            session.dispatcher.stopAccepting();
            awaitQuiesce(session);
        }
        if (syncTaskDao.transition(taskId, TaskState.RUNNING, TaskState.PAUSED) == 0) {
            throw new DbSyncException(ErrorCode.TASK_STATE_INVALID,
                    "任务状态已被其他实例改变，暂停失败（taskId=" + taskId + "）");
        }
        resetUnfinished(taskId, "暂停");
        log.info("任务已暂停 task={} shards={}", taskId, shardDao.findByTask(taskId).size());
    }

    /**
     * 恢复：从断点继续，<b>不重建分片</b>。
     *
     * @param taskId 任务 ID
     */
    public void resume(long taskId) {
        SyncTask task = requireTask(taskId);
        if (task.getState() != TaskState.PAUSED) {
            throw new DbSyncException(ErrorCode.TASK_STATE_INVALID,
                    "只有 PAUSED 状态的任务可以恢复，当前为 " + task.getState());
        }
        resetUnfinished(taskId, "恢复");
        if (syncTaskDao.transition(taskId, TaskState.PAUSED, TaskState.RUNNING) == 0) {
            throw new DbSyncException(ErrorCode.TASK_STATE_INVALID,
                    "任务状态已被其他实例改变，恢复失败（taskId=" + taskId + "）");
        }
        Session session = new Session();
        TaskRuntime runtime = runtimeFactory.create(taskId, session.cancel::get);
        launch(taskId, task, runtime, session);
        log.info("任务已恢复 task={}", taskId);
    }

    /**
     * 取消：停止调度并把任务置为 FAILED（保留断点，修复后可重跑）。
     *
     * @param taskId 任务 ID
     */
    public void cancel(long taskId) {
        SyncTask task = requireTask(taskId);
        if (task.getState().isTerminal()) {
            throw new DbSyncException(ErrorCode.TASK_STATE_INVALID,
                    "任务已是终态 " + task.getState() + "，不能取消");
        }
        Session session = sessions.remove(taskId);
        if (session != null) {
            session.cancel.set(true);
            session.dispatcher.stopAccepting();
            awaitQuiesce(session);
        }
        resetUnfinished(taskId, "取消");
        syncTaskDao.markFailed(taskId, "CANCELLED：操作人取消");
        log.warn("任务已取消 task={}", taskId);
    }

    /**
     * 实例启动时接管遗留的 RUNNING 任务（不重建分片）。
     *
     * @param taskId 任务 ID
     */
    public void recover(long taskId) {
        SyncTask task = requireTask(taskId);
        if (task.getState() != TaskState.RUNNING) {
            log.info("任务不是 RUNNING，无需接管 task={} state={}", taskId, task.getState());
            return;
        }
        if (isRunning(taskId)) {
            log.info("任务已在本实例运行，忽略接管 task={}", taskId);
            return;
        }
        // 只回收过期租约：仍被别人合法持有的分片不能碰
        int taken = shardDao.takeOverExpired(taskId, instanceId, leaseManager.leaseDuration());
        log.info("接管遗留任务 task={} 回收过期租约分片={}", taskId, taken);
        Session session = new Session();
        TaskRuntime runtime = runtimeFactory.create(taskId, session.cancel::get);
        launch(taskId, task, runtime, session);
    }

    /**
     * 进度快照。
     *
     * @param taskId 任务 ID
     * @return 进度
     */
    public MigrationProgress progress(long taskId) {
        SyncTask task = requireTask(taskId);
        Map<ShardState, Integer> counts = new EnumMap<>(ShardState.class);
        int total = 0;
        for (Map.Entry<ShardState, Integer> entry : shardDao.countByState(taskId).entrySet()) {
            counts.put(entry.getKey(), entry.getValue());
            total += entry.getValue();
        }
        long committedRows = shardDao.sumCommittedRows(taskId);
        long estimatedRows = 0L;
        for (TableMapping mapping : tableMappingDao.findByTask(taskId)) {
            if (mapping.getRowCountEstimate() > 0) {
                estimatedRows += mapping.getRowCountEstimate();
            }
        }
        return new MigrationProgress(taskId, task.getState(), total, counts, committedRows,
                estimatedRows);
    }

    /**
     * 本实例是否正在跑该任务。
     *
     * @param taskId 任务 ID
     * @return true 表示有活着的调度线程
     */
    public boolean isRunning(long taskId) {
        Session session = sessions.get(taskId);
        return session != null && session.thread != null && session.thread.isAlive();
    }

    /**
     * 启动调度线程。
     *
     * <p><b>限速器注销</b>（MIG-05）：调度线程退出的<b>唯一</b>出口是 {@code finally}，
     * 因此"任务跑完 / 暂停后收手 / 取消 / 调度异常"四种结束路径都会把
     * {@code taskId → Throttle} 从注册表摘掉，注册表大小始终等于"本实例正在跑的任务数"。
     * 不在这里清理的话，长跑实例会把每个跑过的任务的限速器永久留在内存里。
     *
     * @param taskId  任务 ID
     * @param task    任务
     * @param runtime 运行时
     * @param session 会话
     */
    private void launch(long taskId, SyncTask task, TaskRuntime runtime, Session session) {
        ShardDispatcher dispatcher = new ShardDispatcher(taskId, instanceId,
                task.getConcurrency(), leaseManager, shardDao, pools, runtime::newWorker);
        session.dispatcher = dispatcher;
        Thread thread = new Thread(() -> {
            try {
                dispatcher.run();
            } finally {
                // 先注销限速器：此刻已无 worker 在跑，摘掉不会有人再用到旧实例
                throttleRegistry.remove(taskId);
            }
        }, "migration-" + taskId);
        thread.setDaemon(true);
        session.thread = thread;
        sessions.put(taskId, session);
        thread.start();
        log.info("调度线程已启动 task={} concurrency={} shards={} throttle={}", taskId,
                task.getConcurrency(), shardDao.findByTask(taskId).size(),
                throttleRegistry.find(taskId).orElse(null));
    }

    /**
     * 把未完成的分片置回 PENDING（pause/resume/cancel 共用）。
     *
     * @param taskId 任务 ID
     * @param reason 原因（日志用）
     */
    private void resetUnfinished(long taskId, String reason) {
        for (Shard shard : shardDao.findByTask(taskId)) {
            ShardState state = shard.getState();
            if (state == ShardState.SUCCESS || state == ShardState.DEAD) {
                continue;
            }
            shardDao.resetPending(shard.getId());
            log.debug("{}：分片置回 PENDING shard={} was={}", reason, shard.getShardNo(), state);
        }
    }

    /**
     * 等待在跑分片收手（有上限，超时记警告——租约到期后会被接管，数据不会丢）。
     *
     * @param session 会话
     */
    private void awaitQuiesce(Session session) {
        if (session.dispatcher == null) {
            return;
        }
        try {
            boolean done = session.dispatcher.awaitFinished(QUIESCE_TIMEOUT);
            if (!done) {
                log.warn("停止接收后 {}s 内仍有分片未收尾（租约到期后会被接管）",
                        QUIESCE_TIMEOUT.toSeconds());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待分片收尾被中断");
        }
    }

    /**
     * 读任务。
     *
     * @param taskId 任务 ID
     * @return 任务
     */
    private SyncTask requireTask(long taskId) {
        Optional<SyncTask> found = syncTaskDao.findById(taskId);
        if (found.isEmpty()) {
            throw new DbSyncException(ErrorCode.TASK_NOT_FOUND, "任务不存在：" + taskId);
        }
        return found.get();
    }

    /**
     * 本实例内的任务会话（仅保存易失状态，持久状态一律在元数据库）。
     */
    private static final class Session {

        /** 暂停/取消信号。 */
        private final AtomicBoolean cancel = new AtomicBoolean(false);

        /** 调度器（launch 前为 null）。 */
        private volatile ShardDispatcher dispatcher;

        /** 调度线程。 */
        private volatile Thread thread;
    }
}
