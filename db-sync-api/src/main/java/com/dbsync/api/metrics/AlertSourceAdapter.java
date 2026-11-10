package com.dbsync.api.metrics;

import com.dbsync.api.alert.AlertService;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.model.SyncTask;
import com.dbsync.core.port.IncrementalMetricsPort;
import com.dbsync.metadata.dao.DataSourceDao;
import com.dbsync.metadata.dao.DeadLetterDao;
import com.dbsync.metadata.dao.ShardDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 告警数据源适配器：把元数据库 + 指标端口适配成 {@link AlertService.AlertSource}。
 *
 * <p>{@code // ADDED:} 告警规则需要"全局视角"（延迟/失败率取全部任务的最大值），
 * 而 {@code AlertPort} 只是单向下发端口，没有取数能力，故单独放宽一个取数适配器。
 *
 * <p>所有方法"不抛异常"，取不到数据按 0 处理（告警宁可漏报也不要把定时任务打挂）。
 */
public class AlertSourceAdapter implements AlertService.AlertSource {

    private static final Logger log = LoggerFactory.getLogger(AlertSourceAdapter.class);

    /** 失败率告警阈值（与 Gate 默认一致）。 */
    private final double failRateThreshold;
    /** 延迟告警阈值（毫秒）。 */
    private final long lagThresholdMs;

    private final SyncTaskDao syncTaskDao;
    private final ShardDao shardDao;
    private final DataSourceDao dataSourceDao;
    private final DeadLetterDao deadLetterDao;
    private final IncrementalMetricsPort metricsPort;

    /**
     * 构造。
     *
     * @param syncTaskDao       任务 DAO
     * @param shardDao          分片 DAO
     * @param dataSourceDao     数据源 DAO
     * @param deadLetterDao     死信 DAO
     * @param metricsPort       增量指标端口
     * @param lagThresholdMs    延迟阈值
     * @param failRateThreshold 失败率阈值
     */
    public AlertSourceAdapter(SyncTaskDao syncTaskDao, ShardDao shardDao,
                              DataSourceDao dataSourceDao, DeadLetterDao deadLetterDao,
                              IncrementalMetricsPort metricsPort,
                              long lagThresholdMs, double failRateThreshold) {
        this.syncTaskDao = syncTaskDao;
        this.shardDao = shardDao;
        this.dataSourceDao = dataSourceDao;
        this.deadLetterDao = deadLetterDao;
        this.metricsPort = metricsPort;
        this.lagThresholdMs = lagThresholdMs;
        this.failRateThreshold = failRateThreshold;
    }

    @Override
    public long lagP95Ms() {
        long max = 0L;
        for (SyncTask task : activeTasks()) {
            max = Math.max(max, latencyOf(task.getId()));
        }
        return max;
    }

    @Override
    public long lagThresholdMs() {
        return lagThresholdMs;
    }

    @Override
    public double failRate() {
        double max = 0.0d;
        for (SyncTask task : activeTasks()) {
            max = Math.max(max, failRateOf(task.getId()));
        }
        return max;
    }

    @Override
    public double failRateThreshold() {
        return failRateThreshold;
    }

    @Override
    public List<Long> failedTaskIds() {
        List<Long> ids = new ArrayList<>();
        try {
            for (SyncTask task : syncTaskDao.findByState(TaskState.FAILED)) {
                ids.add(Long.valueOf(task.getId()));
            }
        } catch (RuntimeException e) {
            log.debug("读取失败任务失败 err={}", e.toString());
        }
        return ids;
    }

    /**
     * 任务名（邮件正文展示用）。
     *
     * <p>只按主键查单条：告警正文最多展示 5 个任务，一次一封邮件查 5 次单行索引查询，
     * 相对于"值班看不出是哪个任务失败"的代价可以忽略。取不到一律返回 null
     * （正文退化为展示任务 ID），绝不因为展示需求让告警下发失败。
     *
     * @param taskId 任务 ID
     * @return 任务名；取不到返回 null
     */
    @Override
    public String taskNameOf(long taskId) {
        try {
            return syncTaskDao.findById(taskId).map(SyncTask::getName).orElse(null);
        } catch (RuntimeException e) {
            log.debug("读取任务名失败 taskId={} err={}", Long.valueOf(taskId), e.toString());
            return null;
        }
    }

    @Override
    public long deadLetterOpen() {
        try {
            return deadLetterDao.countOpenAll();
        } catch (RuntimeException e) {
            log.debug("死信数读取失败 err={}", e.toString());
            return 0L;
        }
    }

    @Override
    public int downDataSourceCount() {
        try {
            int down = 0;
            for (com.dbsync.common.model.DataSourceDef def : dataSourceDao.findAll()) {
                if ("DOWN".equalsIgnoreCase(def.getHealthStatus())) {
                    down++;
                }
            }
            return down;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public int takeoverInLast10Minutes() {
        // 本期不落"接管事件表"：用分片 attempt>1 的规模近似（见 04 文档 §5 P1 项）
        try {
            int suspicious = 0;
            for (SyncTask task : activeTasks()) {
                for (com.dbsync.common.model.Shard shard : shardDao.findByTask(task.getId())) {
                    if (shard.getAttempt() > 1) {
                        suspicious++;
                    }
                }
            }
            return suspicious;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private List<SyncTask> activeTasks() {
        try {
            return syncTaskDao.findByState(TaskState.RUNNING);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private long latencyOf(long taskId) {
        try {
            return metricsPort.latencyP95(taskId);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private double failRateOf(long taskId) {
        try {
            return metricsPort.failRate(taskId, Duration.ofMinutes(15L));
        } catch (RuntimeException e) {
            return 0.0d;
        }
    }
}
