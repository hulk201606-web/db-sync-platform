package com.dbsync.server.config;

import com.dbsync.api.cdc.CdcContextFactory;
import com.dbsync.api.cdc.CdcRuntimeManager;
import com.dbsync.api.cdc.CdcRuntimeSupervisor;
import com.dbsync.cdc.CdcCaptureScheduler;
import com.dbsync.cdc.ChangeCollector;
import com.dbsync.cdc.ChangePoller;
import com.dbsync.cdc.ChangeQueue;
import com.dbsync.cdc.CheckpointCursorStore;
import com.dbsync.cdc.PollerCursorStore;
import com.dbsync.cdc.TimestampPoller;
import com.dbsync.cdc.TriggerLogPoller;
import com.dbsync.cdc.WatermarkStore;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.core.lease.InstanceIdProvider;
import com.dbsync.core.pool.NamedThreadPoolManager;
import com.dbsync.metadata.dao.CheckpointDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import com.dbsync.metadata.dao.TriggerDefDao;
import com.dbsync.metadata.dao.WatermarkDao;
import com.dbsync.migration.DeadLetterService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 增量<b>运行期</b>装配（CDC-02：把 {@code ChangeConsumer} 接上生命周期；
 * 本轮补齐<b>采集侧</b>与<b>冲突策略持久化</b>的装配）。
 *
 * <p>{@link CdcConfig} 只装配无状态/共享组件；本类装配"按任务启停"的那一层，
 * 并补上 {@link CdcConfig} 类注释里承诺但此前没人做的三件事：
 * <ol>
 *   <li><b>启动恢复</b>：{@link ApplicationReadyEvent} 后扫描所有 {@code RUNNING} 任务并拉起消费
 *       （实例重启后库里是 RUNNING、进程里没有会话，必须补）；</li>
 *   <li><b>周期对账</b>：{@code @Scheduled} 收敛"库状态已不是 RUNNING 但会话还在跑"的漂移
 *       （别的实例暂停/取消、或某入口的停止调用抛过异常）；</li>
 *   <li><b>采集调度</b>（本轮新增）：{@link CdcCaptureScheduler} 按任务周期把源库日志搬进
 *       {@code sync_change_log}。它的启停<b>不单独注册</b>，而是由
 *       {@link CdcRuntimeManager} 与消费<b>成对</b>启停——同一个真相，
 *       不会出现"消费停了采集还在跑"。</li>
 * </ol>
 *
 * <p><b>不影响启动</b>：恢复过程里单任务失败只记 {@code log.error} + 运行态 {@code lastError}，
 * 绝不向上抛——增量消费是后台关注点，不该让平台起不来。
 *
 * <p><b>多实例</b>：不选主。同一任务在多个实例都可能有会话，谁真正消费由
 * {@code WatermarkStore.acquireAnyGroup} 的 CAS 组租约决定；谁真正<b>采集</b>
 * 由 {@code CheckpointCursorStore.tryClaim} 的位点行版本 CAS 决定（两者都是既有机制，不引入选主）。
 */
@Configuration
public class CdcRuntimeConfig {

    /** 空转轮询间隔（组内没变更时的等待）。 */
    private static final Duration CDC_POLL_INTERVAL = Duration.ofMillis(200L);

    /**
     * 增量上下文工厂（按任务装配 18 个字段）。
     *
     * @param registry          数据源注册表
     * @param syncTaskDao       任务 DAO
     * @param tableMappingDao   表映射 DAO
     * @param changeQueue       变更队列
     * @param watermarkStore    水位仓
     * @param deadLetterService 死信服务（其 {@code create} 即 {@code DeadLetterWriter} 端口实现）
     * @param instanceIdProvider 实例标识
     * @param properties        配置（组租期用既有 lease 配置，不新增配置项）
     * @return 工厂
     */
    @Bean
    public CdcContextFactory cdcContextFactory(DataSourceRegistry registry,
                                               SyncTaskDao syncTaskDao,
                                               TableMappingDao tableMappingDao,
                                               ChangeQueue changeQueue,
                                               WatermarkStore watermarkStore,
                                               DeadLetterService deadLetterService,
                                               InstanceIdProvider instanceIdProvider,
                                               DbSyncProperties properties) {
        return new CdcContextFactory(registry, syncTaskDao, tableMappingDao, changeQueue,
                watermarkStore, deadLetterService::create, instanceIdProvider.instanceId(),
                properties.leaseDuration(), CDC_POLL_INTERVAL);
    }

    /**
     * 增量消费运行期管理器（进程内单例，按 taskId 启停）。
     *
     * <p>同时注入<b>采集调度器</b>与<b>水位 DAO</b>：前者让"采集"与"消费"同源启停
     * （见 {@link CdcRuntimeManager} 类注释），后者供运行态视图判定
     * {@code anyInstanceConsuming}（跨实例是否有活跃消费者）。
     *
     * @param pools              线程池管理器
     * @param cdcContextFactory  上下文工厂
     * @param syncTaskDao        任务 DAO（运行态视图补配置值）
     * @param instanceIdProvider 实例标识（= 消费者 ID）
     * @param watermarkDao       水位 DAO（跨实例消费判定）
     * @param captureScheduler   采集调度器
     * @return 管理器
     */
    @Bean
    public CdcRuntimeManager cdcRuntimeManager(NamedThreadPoolManager pools,
                                               CdcContextFactory cdcContextFactory,
                                               SyncTaskDao syncTaskDao,
                                               InstanceIdProvider instanceIdProvider,
                                               WatermarkDao watermarkDao,
                                               CdcCaptureScheduler captureScheduler) {
        return new CdcRuntimeManager(pools, cdcContextFactory, syncTaskDao,
                instanceIdProvider.instanceId(), watermarkDao, captureScheduler);
    }

    /**
     * 采集位点存储（落 {@code sync_checkpoint(scope_type='POLLER')}）。
     *
     * <p>这一步是"重启后不重复采集/不漏采集"的全部实现所在：位点不在内存里，
     * 进程重启后轮询器读到的就是上次采到的位置。
     *
     * <p><b>采集权窗口 = 全局采集周期</b>：任务级未配置 {@code poll_interval_ms} 时
     * 调度器用的是 {@code dbsync.cdc.poll-interval-ms}，两者必须一致，否则会出现
     * "窗口比周期长 → 每隔一轮被自己挡掉"的假性延迟。任务级配了周期时，
     * 轮询器会把任务自己的周期当窗口传下来（见 {@code TriggerLogPoller.pollOnce}）。
     *
     * @param checkpointDao 断点 DAO
     * @param properties    配置（全局采集周期）
     * @return 位点存储
     */
    @Bean
    public PollerCursorStore pollerCursorStore(CheckpointDao checkpointDao,
                                               DbSyncProperties properties) {
        return new CheckpointCursorStore(checkpointDao,
                Duration.ofMillis(properties.getCdc().getPollIntervalMs()));
    }

    /**
     * 触发器日志采集器（{@code TRIGGER_LOG} 模式）。
     *
     * @param registry        数据源注册表
     * @param syncTaskDao     任务 DAO
     * @param tableMappingDao 表映射 DAO
     * @param triggerDefDao   触发器台账 DAO
     * @param changeQueue     变更队列
     * @param cursors         位点存储
     * @param properties      配置（采集批大小）
     * @return 采集器
     */
    @Bean
    public TriggerLogPoller triggerLogPoller(DataSourceRegistry registry, SyncTaskDao syncTaskDao,
                                             TableMappingDao tableMappingDao,
                                             TriggerDefDao triggerDefDao,
                                             ChangeQueue changeQueue,
                                             PollerCursorStore cursors,
                                             DbSyncProperties properties) {
        return new TriggerLogPoller(registry, syncTaskDao, tableMappingDao, triggerDefDao,
                changeQueue, properties.getCdc().getBatchSize(), cursors);
    }

    /**
     * 时间戳采集器（{@code TIMESTAMP_VERSION} 模式）。
     *
     * @param registry        数据源注册表
     * @param syncTaskDao     任务 DAO
     * @param tableMappingDao 表映射 DAO
     * @param changeQueue     变更队列
     * @param cursors         位点存储
     * @param properties      配置（采集批大小）
     * @return 采集器
     */
    @Bean
    public TimestampPoller timestampPoller(DataSourceRegistry registry, SyncTaskDao syncTaskDao,
                                           TableMappingDao tableMappingDao,
                                           ChangeQueue changeQueue, PollerCursorStore cursors,
                                           DbSyncProperties properties) {
        return new TimestampPoller(registry, syncTaskDao, tableMappingDao, changeQueue,
                properties.getCdc().getBatchSize(), cursors);
    }

    /**
     * 一轮采集的执行者（模式 → 采集器的唯一注册点）。
     *
     * <p>注册表里<b>不包含</b> {@code SDK_WRITE}：该模式的变更由业务侧双写进
     * {@code sync_change_log}，平台再轮询源库会把同一份变更记两遍。
     *
     * @param syncTaskDao     任务 DAO
     * @param tableMappingDao 表映射 DAO
     * @param triggerLogPoller 触发器采集器
     * @param timestampPoller  时间戳采集器
     * @return 采集者
     */
    @Bean
    public ChangeCollector changeCollector(SyncTaskDao syncTaskDao,
                                           TableMappingDao tableMappingDao,
                                           TriggerLogPoller triggerLogPoller,
                                           TimestampPoller timestampPoller) {
        return new ChangeCollector(syncTaskDao, tableMappingDao,
                List.<ChangePoller>of(triggerLogPoller, timestampPoller));
    }

    /**
     * 采集调度器（按 taskId 的周期采集 + 重入保护 + 失败退避）。
     *
     * <p><b>周期取值</b>：任务级 {@code sync_task.poll_interval_ms} 优先，
     * 未配置时回落 {@code dbsync.cdc.poll-interval-ms}（默认 5s）。
     *
     * @param collector  采集者
     * @param syncTaskDao 任务 DAO
     * @param properties 配置（全局默认周期）
     * @return 调度器
     */
    @Bean(destroyMethod = "close")
    public CdcCaptureScheduler cdcCaptureScheduler(ChangeCollector collector,
                                                   SyncTaskDao syncTaskDao,
                                                   DbSyncProperties properties) {
        return new CdcCaptureScheduler(collector, syncTaskDao,
                Duration.ofMillis(properties.getCdc().getPollIntervalMs()));
    }

    /**
     * 增量消费对账器（启动恢复 + 漂移补偿）。
     *
     * @param runtime     运行期管理器
     * @param syncTaskDao 任务 DAO
     * @return 对账器
     */
    @Bean
    public CdcRuntimeSupervisor cdcRuntimeSupervisor(CdcRuntimeManager runtime,
                                                     SyncTaskDao syncTaskDao) {
        return new CdcRuntimeSupervisor(runtime, syncTaskDao);
    }

    /**
     * 生命周期钩子（应用就绪 → 恢复；周期 → 对账）。
     *
     * @param supervisor 对账器
     * @return 钩子
     */
    @Bean
    public CdcRuntimeLifecycle cdcRuntimeLifecycle(CdcRuntimeSupervisor supervisor) {
        return new CdcRuntimeLifecycle(supervisor);
    }

    /**
     * 应用生命周期钩子（独立组件承载 {@code @EventListener}/{@code @Scheduled}，
     * 管理器/对账器保持纯 POJO 可直接单测——与 {@link SchedulerConfig.JobScheduler} 同一手法）。
     */
    public static class CdcRuntimeLifecycle {

        private static final Logger log = LoggerFactory.getLogger(CdcRuntimeLifecycle.class);

        private final CdcRuntimeSupervisor supervisor;
        /** 对账重入保护：调度线程池 >1 后两次触发可能重叠，与 SchedulerConfig 的 4 个作业同款。 */
        private final AtomicBoolean reconciling = new AtomicBoolean(false);

        /**
         * 构造。
         *
         * @param supervisor 对账器
         */
        public CdcRuntimeLifecycle(CdcRuntimeSupervisor supervisor) {
            this.supervisor = supervisor;
        }

        /**
         * 应用就绪：把遗留的 {@code RUNNING} 任务重新拉起消费。
         */
        @EventListener(ApplicationReadyEvent.class)
        public void onApplicationReady() {
            try {
                supervisor.recover();
            } catch (RuntimeException e) {
                // 兜底：恢复失败绝不能让应用启动失败
                log.error("增量消费启动恢复异常（已忽略，不影响启动）", e);
            }
        }

        /**
         * 周期对账：停掉"状态已不允许消费"的会话。
         */
        @Scheduled(initialDelayString = "${dbsync.jobs.cdc-reconcile-ms:30000}",
                fixedDelayString = "${dbsync.jobs.cdc-reconcile-ms:30000}")
        public void reconcile() {
            if (!reconciling.compareAndSet(false, true)) {
                return;
            }
            try {
                int stopped = supervisor.reconcile();
                if (stopped > 0) {
                    log.warn("增量消费对账：本轮停止 {} 个状态漂移的会话", Integer.valueOf(stopped));
                }
            } catch (RuntimeException e) {
                log.warn("增量消费对账异常（已忽略，下轮重试）", e);
            } finally {
                reconciling.set(false);
            }
        }
    }
}
