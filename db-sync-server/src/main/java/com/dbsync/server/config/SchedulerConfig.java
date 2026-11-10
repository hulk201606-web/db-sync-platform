package com.dbsync.server.config;

import com.dbsync.api.alert.AlertService;
import com.dbsync.api.schedule.HealthProbeJob;
import com.dbsync.api.schedule.HeartbeatJob;
import com.dbsync.api.schedule.LeaseReaperJob;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.core.lease.InstanceIdProvider;
import com.dbsync.core.port.IncrementalMetricsPort;
import com.dbsync.metadata.dao.CompareJobDao;
import com.dbsync.metadata.dao.CutoverPlanDao;
import com.dbsync.metadata.dao.DataSourceDao;
import com.dbsync.metadata.dao.DeadLetterDao;
import com.dbsync.metadata.dao.InstanceDao;
import com.dbsync.metadata.dao.ShardDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.api.metrics.SyncMetrics;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 定时作业装配（心跳 / 租约回收 / 数据源探活 / 告警评估）。
 *
 * <p>每个作业都是"单实例内互斥"的（作业自己用 {@link AtomicBoolean} 防重入），
 * 多实例之间靠元数据库的状态（租约、心跳）协作，而不是靠调度锁——
 * 这样某个实例卡住不会拖死整个集群。
 *
 * <p>调度线程池大小由 {@code spring.task.scheduling.pool.size}（application.yml = 5）控制，
 * 让这些作业并行而不是串行；因此每个 {@code @Scheduled} 入口都必须有 CAS 防重入。
 */
@Configuration
public class SchedulerConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulerConfig.class);

    /**
     * 心跳作业。
     *
     * @param instanceDao 实例 DAO
     * @param syncTaskDao 任务 DAO
     * @param shardDao    分片 DAO
     * @param deadLetterDao 死信 DAO
     * @param compareJobDao 作业 DAO
     * @param cutoverPlanDao 计划 DAO
     * @param metricsPort 指标端口
     * @param syncMetrics 业务指标
     * @param instanceIdProvider 实例标识
     * @return 作业
     */
    @Bean
    public HeartbeatJob heartbeatJob(InstanceDao instanceDao, SyncTaskDao syncTaskDao,
                                     ShardDao shardDao, DeadLetterDao deadLetterDao,
                                     CompareJobDao compareJobDao, CutoverPlanDao cutoverPlanDao,
                                     IncrementalMetricsPort metricsPort, SyncMetrics syncMetrics,
                                     InstanceIdProvider instanceIdProvider) {
        return new HeartbeatJob(instanceDao, syncTaskDao, shardDao, deadLetterDao, compareJobDao,
                cutoverPlanDao, metricsPort, syncMetrics, instanceIdProvider.instanceId());
    }

    /**
     * 租约回收作业。
     *
     * @param instanceDao 实例 DAO
     * @param syncTaskDao 任务 DAO
     * @param shardDao    分片 DAO
     * @param instanceIdProvider 实例标识
     * @return 作业
     */
    @Bean
    public LeaseReaperJob leaseReaperJob(InstanceDao instanceDao, SyncTaskDao syncTaskDao,
                                         ShardDao shardDao,
                                         InstanceIdProvider instanceIdProvider) {
        return new LeaseReaperJob(instanceDao, syncTaskDao, shardDao,
                instanceIdProvider.instanceId());
    }

    /**
     * 数据源探活作业。
     *
     * @param dataSourceDao 数据源 DAO
     * @param registry      注册表
     * @return 作业
     */
    @Bean
    public HealthProbeJob healthProbeJob(DataSourceDao dataSourceDao, DataSourceRegistry registry) {
        return new HealthProbeJob(dataSourceDao, registry);
    }

    /**
     * 调度触发器：把 4 个作业挂到 Spring 调度器上。
     *
     * <p>// ADDED: 04 文档只给了作业类（{@code HeartbeatJob} 等），没有规定"谁按时调用"。
     * 用独立组件承载 {@code @Scheduled} 方法，作业类保持纯 POJO 可直接单测。
     *
     * @param heartbeatJob   心跳
     * @param leaseReaperJob 回收
     * @param healthProbeJob 探活
     * @param alertService   告警
     * @return 触发器
     */
    @Bean
    public JobScheduler jobScheduler(HeartbeatJob heartbeatJob, LeaseReaperJob leaseReaperJob,
                                     HealthProbeJob healthProbeJob, AlertService alertService) {
        return new JobScheduler(heartbeatJob, leaseReaperJob, healthProbeJob, alertService);
    }

    /**
     * 作业触发器（Spring 调度入口）。
     */
    public static class JobScheduler {

        private final HeartbeatJob heartbeatJob;
        private final LeaseReaperJob leaseReaperJob;
        private final HealthProbeJob healthProbeJob;
        private final AlertService alertService;
        private final AtomicBoolean heartbeatRunning = new AtomicBoolean(false);
        private final AtomicBoolean reaperRunning = new AtomicBoolean(false);
        private final AtomicBoolean probeRunning = new AtomicBoolean(false);
        private final AtomicBoolean alertRunning = new AtomicBoolean(false);

        /**
         * 构造。
         *
         * @param heartbeatJob   心跳作业
         * @param leaseReaperJob 回收作业
         * @param healthProbeJob 探活作业
         * @param alertService   告警服务
         */
        public JobScheduler(HeartbeatJob heartbeatJob, LeaseReaperJob leaseReaperJob,
                            HealthProbeJob healthProbeJob, AlertService alertService) {
            this.heartbeatJob = heartbeatJob;
            this.leaseReaperJob = leaseReaperJob;
            this.healthProbeJob = healthProbeJob;
            this.alertService = alertService;
        }

        /**
         * 心跳（写实例存活 + 刷新指标）。
         */
        @Scheduled(initialDelayString = "${dbsync.jobs.heartbeat-ms:15000}",
                fixedDelayString = "${dbsync.jobs.heartbeat-ms:15000}")
        public void heartbeat() {
            if (!heartbeatRunning.compareAndSet(false, true)) {
                return;
            }
            try {
                heartbeatJob.runOnce();
            } catch (RuntimeException e) {
                log.warn("心跳作业异常（已忽略，下轮重试）", e);
            } finally {
                heartbeatRunning.set(false);
            }
        }

        /**
         * 租约回收（把过期租约的分片放回 PENDING）。
         */
        @Scheduled(initialDelayString = "${dbsync.jobs.lease-reap-ms:20000}",
                fixedDelayString = "${dbsync.jobs.lease-reap-ms:20000}")
        public void reapLeases() {
            if (!reaperRunning.compareAndSet(false, true)) {
                return;
            }
            try {
                leaseReaperJob.runOnce();
            } catch (RuntimeException e) {
                log.warn("租约回收异常（已忽略，下轮重试）", e);
            } finally {
                reaperRunning.set(false);
            }
        }

        /**
         * 数据源探活。
         */
        @Scheduled(initialDelayString = "${dbsync.jobs.health-probe-ms:60000}",
                fixedDelayString = "${dbsync.jobs.health-probe-ms:60000}")
        public void probeDataSources() {
            if (!probeRunning.compareAndSet(false, true)) {
                return;
            }
            try {
                int down = healthProbeJob.runOnce();
                if (down > 0) {
                    log.warn("本次探活发现 {} 个数据源 DOWN", Integer.valueOf(down));
                }
            } catch (RuntimeException e) {
                log.warn("数据源探活异常（已忽略，下轮重试）", e);
            } finally {
                probeRunning.set(false);
            }
        }

        /**
         * 告警评估。
         */
        @Scheduled(initialDelayString = "${dbsync.jobs.alert-evaluate-ms:30000}",
                fixedDelayString = "${dbsync.jobs.alert-evaluate-ms:30000}")
        public void evaluateAlerts() {
            if (!alertRunning.compareAndSet(false, true)) {
                return;
            }
            try {
                List<AlertService.AlertRecord> fired = alertService.evaluate();
                if (!fired.isEmpty()) {
                    log.warn("本轮触发 {} 条告警", Integer.valueOf(fired.size()));
                }
            } catch (RuntimeException e) {
                log.warn("告警评估异常（已忽略，下轮重试）", e);
            } finally {
                alertRunning.set(false);
            }
        }
    }
}
