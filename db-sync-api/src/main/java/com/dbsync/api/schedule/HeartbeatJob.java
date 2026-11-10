package com.dbsync.api.schedule;

import com.dbsync.api.metrics.SyncMetrics;
import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.enums.ShardState;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.model.CutoverPlan;
import com.dbsync.common.model.SyncTask;
import com.dbsync.core.port.IncrementalMetricsPort;
import com.dbsync.metadata.dao.CompareJobDao;
import com.dbsync.metadata.dao.CutoverPlanDao;
import com.dbsync.metadata.dao.DeadLetterDao;
import com.dbsync.metadata.dao.InstanceDao;
import com.dbsync.metadata.dao.ShardDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 心跳 + 指标刷新任务（T44）。
 *
 * <p>每个心跳周期（{@code dbsync.jobs.heartbeat-ms}，默认 15s）做三件事：
 * <ol>
 *   <li>写本实例心跳（{@code sys_instance}），供大盘 / 存活统计使用
 *       （注意：租约回收的判据是 {@code lease_expire_at}，不是心跳）；</li>
 *   <li>刷新 {@link SyncMetrics} 的 Gauge（严格按架构 §11 口径）；</li>
 *   <li>顺带刷新增量指标（延迟 P95 / 积压 / 失败率）。</li>
 * </ol>
 *
 * <p><b>为什么不直接用 {@code @Scheduled}</b>：本模块不引入 {@code spring-context}
 * 的调度注解也能跑（{@code @Scheduled} 需要 {@code @EnableScheduling} 在 server 层开启），
 * 因此提供 {@link #runOnce()}，由 server 的 {@code @Scheduled} 或测试直接驱动，
 * 便于单测"调一次 → 断言 Gauge"。
 *
 * <p><b>存活窗口常量在本类不再定义</b>：原 {@code ALIVE_WINDOW=30s} 是死常量且无人引用，
 * 全平台统一到 {@link InstanceDao#DEFAULT_ALIVE_WINDOW}（90s，见 {@code MonitorService}）。
 */
public class HeartbeatJob {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatJob.class);

    private final InstanceDao instanceDao;
    private final SyncTaskDao syncTaskDao;
    private final ShardDao shardDao;
    private final DeadLetterDao deadLetterDao;
    private final CompareJobDao compareJobDao;
    private final CutoverPlanDao cutoverPlanDao;
    private final IncrementalMetricsPort metricsPort;
    private final SyncMetrics syncMetrics;
    private final String instanceId;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 构造。
     *
     * @param instanceDao    实例 DAO
     * @param syncTaskDao    任务 DAO
     * @param shardDao       分片 DAO
     * @param deadLetterDao  死信 DAO
     * @param compareJobDao  比对作业 DAO
     * @param cutoverPlanDao 切流计划 DAO
     * @param metricsPort    增量指标端口
     * @param syncMetrics    指标
     * @param instanceId     本实例 ID
     */
    public HeartbeatJob(InstanceDao instanceDao, SyncTaskDao syncTaskDao, ShardDao shardDao,
                        DeadLetterDao deadLetterDao, CompareJobDao compareJobDao,
                        CutoverPlanDao cutoverPlanDao, IncrementalMetricsPort metricsPort,
                        SyncMetrics syncMetrics, String instanceId) {
        this.instanceDao = instanceDao;
        this.syncTaskDao = syncTaskDao;
        this.shardDao = shardDao;
        this.deadLetterDao = deadLetterDao;
        this.compareJobDao = compareJobDao;
        this.cutoverPlanDao = cutoverPlanDao;
        this.metricsPort = metricsPort;
        this.syncMetrics = syncMetrics;
        this.instanceId = instanceId;
    }

    /**
     * 执行一次（幂等，允许并发调用被拒绝）。
     *
     * @return 本次刷新的任务数
     */
    public int runOnce() {
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            List<SyncTask> tasks = syncTaskDao.findAll();
            int shards = 0;
            long instanceShards = 0L;
            for (SyncTask task : tasks) {
                Map<ShardState, Integer> counts = shardDao.countByState(task.getId());
                for (Map.Entry<ShardState, Integer> entry : counts.entrySet()) {
                    syncMetrics.setGauge("migration.shards|taskId=" + task.getId() + ",state="
                            + entry.getKey().name(), entry.getValue().doubleValue());
                    shards += entry.getValue().intValue();
                }
                long lag = safeLong(() -> metricsPort.latencyP95(task.getId()));
                long backlog = safeLong(() -> metricsPort.backlog(task.getId()));
                double failRate = safeDouble(() -> metricsPort.failRate(task.getId(),
                        Duration.ofMinutes(15L)));
                syncMetrics.setGauge("cdc.lag.p95.ms|taskId=" + task.getId(), lag);
                syncMetrics.setGauge("cdc.backlog|taskId=" + task.getId(), backlog);
                syncMetrics.setGauge("cdc.apply.fail.rate|taskId=" + task.getId(), failRate);
                syncMetrics.setGauge("migration.tps|taskId=" + task.getId(),
                        syncMetrics.tps(task.getId()));
                instanceShards += countRunning(counts);
            }
            syncMetrics.setGauge("instance.shards", instanceShards);
            syncMetrics.setGauge("deadletter.open", deadLetterDao.countOpenAll());
            refreshCompare(tasks);
            refreshCutover();
            instanceDao.heartbeat(instanceId, shards, tasks.size());
            return tasks.size();
        } catch (RuntimeException e) {
            log.warn("心跳刷新失败（不影响主流程）", e);
            return 0;
        } finally {
            running.set(false);
        }
    }

    private void refreshCompare(List<SyncTask> tasks) {
        for (SyncTask task : tasks) {
            compareJobDao.latestFinishedSnapshot(task.getId()).ifPresent(snapshot ->
                    syncMetrics.setGauge("compare.pass.rate|taskId=" + task.getId(),
                            snapshot.getPassRate()));
            compareJobDao.findByTask(task.getId(), 1).stream().findFirst().ifPresent(job -> {
                syncMetrics.setGauge("compare.blocks|taskId=" + task.getId() + ",matched=true",
                        job.getTotalBlocks() - job.getDiffBlocks());
                syncMetrics.setGauge("compare.blocks|taskId=" + task.getId() + ",matched=false",
                        job.getDiffBlocks());
            });
        }
    }

    private void refreshCutover() {
        List<SyncTask> tasks = syncTaskDao.findByState(TaskState.RUNNING);
        for (SyncTask task : tasks) {
            for (CutoverPlan plan : cutoverPlanDao.findByTask(task.getId())) {
                syncMetrics.setGauge("cutover.state|planId=" + plan.getId(),
                        stateCode(plan.getIntentState()));
            }
        }
    }

    /**
     * 状态 → 数字。
     *
     * <p>架构 §11 写的是"{@code DRAFT=0 ... ROLLED_BACK=7}"，但 {@link CutoverState} 实际有
     * <b>9 个</b>状态（多了 {@code PRE_CHECK_FAILED} 与 {@code DONE}），8 个数字装不下。
     * 这里采用枚举声明顺序（{@link CutoverState#ordinal()}）作为编码，
     * 保证<b>严格单调</b>且与架构的"数字越大越靠后"语义一致；
     * 偏差已记入交付说明（`ROLLED_BACK=8` 而非 7）。{@code // DEVIATION: §11 状态计数}
     *
     * @param state 状态
     * @return 数字
     */
    public static double stateCode(CutoverState state) {
        return state == null ? 0.0d : state.ordinal();
    }

    private static long countRunning(Map<ShardState, Integer> counts) {
        Integer running = counts.get(ShardState.RUNNING);
        return running == null ? 0L : running.longValue();
    }

    private static long safeLong(java.util.function.LongSupplier supplier) {
        try {
            return supplier.getAsLong();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static double safeDouble(java.util.function.DoubleSupplier supplier) {
        try {
            return supplier.getAsDouble();
        } catch (RuntimeException e) {
            return 0.0d;
        }
    }
}
