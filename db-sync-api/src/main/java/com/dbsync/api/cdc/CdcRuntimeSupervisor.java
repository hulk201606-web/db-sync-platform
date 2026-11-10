package com.dbsync.api.cdc;

import com.dbsync.common.enums.TaskState;
import com.dbsync.common.model.SyncTask;
import com.dbsync.metadata.dao.SyncTaskDao;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 增量消费的"生命周期对账"：启动恢复 + 状态漂移补偿。
 *
 * <p><b>为什么需要它</b>：消费会话是<b>进程内</b>对象，而任务的 {@code state} 在<b>元数据库</b>里。
 * 两者必然会不一致：
 * <ul>
 *   <li>实例重启 → 库里有 RUNNING 任务，进程里没有会话（需要 {@link #recover()}）；</li>
 *   <li>别的实例把任务暂停/取消 → 本实例库里看得到、会话还在跑（需要 {@link #reconcile()}）；</li>
 *   <li>执行顺序漂移（状态改了但停/启消费那一步抛异常）→ 同样靠对账收敛。</li>
 * </ul>
 *
 * <p><b>为什么不在服务层"每个入口都精确调用一次"就算完</b>：入口共有 5 处（迁移 start/pause/
 * resume/cancel、增量 switchMode、任务归档/删除），漏一处就是"任务停了还在写库"。
 * 入口处精确调用负责"及时"，本类负责"最终一致"，两者叠加才可靠。
 *
 * <p><b>多实例</b>：本类只处理"本实例的会话"，不选主、不协调别的实例；
 * 重复消费的防护是组租约 CAS（{@code WatermarkStore.acquireAnyGroup}）。
 */
public class CdcRuntimeSupervisor {

    private static final Logger log = LoggerFactory.getLogger(CdcRuntimeSupervisor.class);

    private final CdcRuntimeManager runtime;
    private final SyncTaskDao syncTaskDao;

    /**
     * 构造。
     *
     * @param runtime     运行期管理器
     * @param syncTaskDao 任务 DAO
     */
    public CdcRuntimeSupervisor(CdcRuntimeManager runtime, SyncTaskDao syncTaskDao) {
        this.runtime = runtime;
        this.syncTaskDao = syncTaskDao;
    }

    /**
     * 启动恢复：把所有 {@code RUNNING} 且增量模式需要消费者的任务拉起消费。
     *
     * <p>单任务失败<b>不影响</b>其它任务、也<b>不影响</b>应用启动（异常已被管理器收敛为
     * {@code log.error} + 运行态 {@code lastError}）。
     *
     * @return 真正拉起的任务数
     */
    public int recover() {
        List<SyncTask> running;
        try {
            running = syncTaskDao.findByState(TaskState.RUNNING);
        } catch (RuntimeException e) {
            log.error("增量消费启动恢复失败：读取 RUNNING 任务异常", e);
            return 0;
        }
        int started = 0;
        for (SyncTask task : running) {
            long taskId = task.getId();
            if (!CdcRuntimeManager.needsConsumer(task.getIncrementalMode())) {
                continue;
            }
            if (runtime.isRunning(taskId)) {
                log.info("启动恢复：任务已在本实例消费，跳过 task={}", taskId);
                continue;
            }
            runtime.start(taskId);
            if (runtime.isRunning(taskId)) {
                started++;
            }
        }
        if (started > 0) {
            log.info("增量消费启动恢复完成 candidates={} started={}", running.size(),
                    Integer.valueOf(started));
        } else {
            log.info("增量消费启动恢复完成 candidates={} started=0", running.size());
        }
        return started;
    }

    /**
     * 状态漂移补偿：本实例在消费、但库里的状态已经不是 {@code RUNNING}（或任务已不存在）时停止消费；
     * 反过来，"库里 RUNNING 但本实例没跑"的任务<b>只告警、不自动重启</b>。
     *
     * <p><b>为什么不自动重启（已拍板的决策）</b>：启动失败最常见的原因是目标库不可用。
     * 若每 30s 自动重试一次，重试本身就成了新的压力源（每个任务每 30s 借一次连接、
     * 跑一次元数据查询），而且会不断刷新 {@code lastError}，让运维看不清真实根因。
     * 因此这里只：
     * <ol>
     *   <li>打一条 {@code log.warn}（带任务 ID 与失败摘要）；</li>
     *   <li><b>保留</b> {@code lastError}，由 {@code GET /cdc/runtime} 暴露给运维；</li>
     *   <li>等待运维显式 resume（或应用重启时的 {@link #recover()}）再起。</li>
     * </ol>
     * 注意 {@link #recover()} 是"进程启动时的恢复"，与这里的周期性重试性质不同：
     * 它每个任务只跑一次，不会形成压力源。
     *
     * @return 被停掉的任务数
     */
    public int reconcile() {
        int stopped = 0;
        for (Long taskId : runtime.runningTaskIds()) {
            Optional<SyncTask> task;
            try {
                task = syncTaskDao.findById(taskId.longValue());
            } catch (RuntimeException e) {
                log.warn("增量消费对账：读取任务失败（本轮跳过）task={} err={}", taskId, e.toString());
                continue;
            }
            if (shouldKeepRunning(task)) {
                continue;
            }
            log.warn("增量消费对账：任务状态已不允许消费，停止会话 task={} state={}", taskId,
                    task.map(t -> t.getState().name()).orElse("MISSING"));
            runtime.stop(taskId.longValue());
            stopped++;
        }
        reportUnstarted();
        return stopped;
    }

    /**
     * 上报"库里 RUNNING 但本实例没跑"的任务（<b>只告警不重启</b>）。
     *
     * <p>只对<b>有 lastError</b> 的任务告警：从未尝试过启动的任务
     * （例如刚被别的实例拉起、或本实例还没轮到）不是异常，不刷日志。
     */
    private void reportUnstarted() {
        List<SyncTask> running;
        try {
            running = syncTaskDao.findByState(TaskState.RUNNING);
        } catch (RuntimeException e) {
            log.debug("增量消费对账：读取 RUNNING 任务失败（跳过告警）err={}", e.toString());
            return;
        }
        for (SyncTask task : running) {
            long taskId = task.getId();
            if (!CdcRuntimeManager.needsConsumer(task.getIncrementalMode())) {
                continue;
            }
            if (runtime.isRunning(taskId)) {
                continue;
            }
            String lastError = runtime.status(taskId).lastError();
            if (lastError == null || lastError.isBlank()) {
                continue;
            }
            log.warn("增量消费对账：任务 RUNNING 但本实例未消费，且上次启动失败；"
                            + "按策略不自动重启（避免每 30s 重试成为压力源），请显式 resume task={} "
                            + "lastError={}", taskId, lastError);
        }
    }

    /**
     * 是否应保持消费。
     *
     * @param task 任务（可能为空：任务被硬删除）
     * @return true 表示应继续消费
     */
    private static boolean shouldKeepRunning(Optional<SyncTask> task) {
        if (task.isEmpty()) {
            return false;
        }
        SyncTask value = task.get();
        return value.getState() == TaskState.RUNNING
                && CdcRuntimeManager.needsConsumer(value.getIncrementalMode());
    }
}
