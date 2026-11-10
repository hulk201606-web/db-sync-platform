package com.dbsync.server.config;

import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.core.lease.InstanceIdProvider;
import com.dbsync.core.lease.ShardLeaseManager;
import com.dbsync.core.pool.NamedThreadPoolManager;
import com.dbsync.core.checkpoint.CheckpointStore;
import com.dbsync.metadata.dao.DeadLetterDao;
import com.dbsync.metadata.dao.ShardDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import com.dbsync.migration.DeadLetterService;
import com.dbsync.migration.MigrationEngine;
import com.dbsync.migration.MigrationPrecheck;
import com.dbsync.migration.RowCountVerifier;
import com.dbsync.core.shard.ShardPlanner;
import com.dbsync.core.throttle.ThrottleRegistry;
import com.dbsync.migration.TaskRuntimeFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 存量迁移装配（T18~T27）。
 *
 * <p>迁移引擎是"按任务起线程池"的长活对象，必须是单例：
 * 它内部持有运行中任务的运行时表，多实例会导致暂停/取消找不到目标任务。
 */
@Configuration
public class MigrationConfig {

    /**
     * 限速器注册表（MIG-05 运行中动态限速的共享句柄）。
     *
     * <p><b>必须是单例</b>：{@code TaskRuntimeFactory} 往里面登记、
     * API 层按 taskId 取出同一个实例做热更新——两个实例就变成"改的和用的不是同一个对象"，
     * 接口返回"已生效"而实际速率毫无变化。
     *
     * @return 注册表
     */
    @Bean
    public ThrottleRegistry throttleRegistry() {
        return new ThrottleRegistry();
    }

    /**
     * 预检（只读，不改数据）。
     *
     * @param syncTaskDao     任务 DAO
     * @param tableMappingDao 表映射 DAO
     * @param registry        数据源注册表
     * @return 预检器
     */
    @Bean
    public MigrationPrecheck migrationPrecheck(SyncTaskDao syncTaskDao,
                                               TableMappingDao tableMappingDao,
                                               DataSourceRegistry registry) {
        return new MigrationPrecheck(syncTaskDao, tableMappingDao, registry);
    }

    /**
     * 核数器（源/目标行数对比）。
     *
     * @param syncTaskDao     任务 DAO
     * @param tableMappingDao 表映射 DAO
     * @param registry        数据源注册表
     * @return 核数器
     */
    @Bean
    public RowCountVerifier rowCountVerifier(SyncTaskDao syncTaskDao,
                                             TableMappingDao tableMappingDao,
                                             DataSourceRegistry registry) {
        return new RowCountVerifier(syncTaskDao, tableMappingDao, registry);
    }

    /**
     * 死信服务（迁移分片失败与增量变更共用的唯一入口）。
     *
     * @param deadLetterDao 死信 DAO
     * @param shardDao      分片 DAO
     * @param applier       变更类死信重放器
     * @return 服务
     */
    @Bean
    public DeadLetterService deadLetterService(DeadLetterDao deadLetterDao, ShardDao shardDao,
                                               ChangeReplayApplier applier) {
        return new DeadLetterService(deadLetterDao, shardDao, applier);
    }

    /**
     * 变更类死信重放器（幂等 upsert 回目标库）。
     *
     * @param tableMappingDao 表映射 DAO
     * @param syncTaskDao     任务 DAO
     * @param registry        数据源注册表
     * @return 重放器
     */
    @Bean
    public ChangeReplayApplier changeReplayApplier(TableMappingDao tableMappingDao,
                                                   SyncTaskDao syncTaskDao,
                                                   DataSourceRegistry registry) {
        return new ChangeReplayApplier(tableMappingDao, syncTaskDao, registry);
    }

    /**
     * 任务运行时工厂（每次启动任务时装配分片上下文）。
     *
     * @param syncTaskDao      任务 DAO
     * @param tableMappingDao  表映射 DAO
     * @param registry         数据源注册表
     * @param checkpointStore  断点存储
     * @param shardDao         分片 DAO
     * @param deadLetterService 死信服务
     * @param throttleRegistry 限速器注册表
     * @return 工厂
     */
    @Bean
    public TaskRuntimeFactory taskRuntimeFactory(SyncTaskDao syncTaskDao,
                                                 TableMappingDao tableMappingDao,
                                                 DataSourceRegistry registry,
                                                 CheckpointStore checkpointStore, ShardDao shardDao,
                                                 DeadLetterService deadLetterService,
                                                 ThrottleRegistry throttleRegistry) {
        return new TaskRuntimeFactory(syncTaskDao, tableMappingDao, registry, checkpointStore,
                shardDao, deadLetterService, throttleRegistry);
    }

    /**
     * 迁移引擎。
     *
     * @param syncTaskDao     任务 DAO
     * @param shardDao        分片 DAO
     * @param tableMappingDao 表映射 DAO
     * @param shardPlanner    分片规划器
     * @param precheck        预检器
     * @param runtimeFactory  运行时工厂
     * @param leaseManager    租约管理器
     * @param pools           线程池管理器
     * @param throttleRegistry 限速器注册表
     * @param instanceIdProvider 实例标识
     * @return 引擎
     */
    @Bean
    public MigrationEngine migrationEngine(SyncTaskDao syncTaskDao, ShardDao shardDao,
                                           TableMappingDao tableMappingDao, ShardPlanner shardPlanner,
                                           MigrationPrecheck precheck,
                                           TaskRuntimeFactory runtimeFactory,
                                           ShardLeaseManager leaseManager,
                                           NamedThreadPoolManager pools,
                                           ThrottleRegistry throttleRegistry,
                                           InstanceIdProvider instanceIdProvider) {
        return new MigrationEngine(syncTaskDao, shardDao, tableMappingDao, shardPlanner, precheck,
                runtimeFactory, leaseManager, pools, throttleRegistry,
                instanceIdProvider.instanceId());
    }
}
