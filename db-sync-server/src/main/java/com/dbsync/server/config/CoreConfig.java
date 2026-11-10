package com.dbsync.server.config;

import com.dbsync.common.enums.ClaimStrategy;
import com.dbsync.common.util.AesGcmCipher;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.core.lease.NoWaitProbeClaimStrategy;
import com.dbsync.core.lease.OptimisticCasClaimStrategy;
import com.dbsync.core.lease.ShardClaimStrategy;
import com.dbsync.core.lease.ShardLeaseManager;
import com.dbsync.core.lease.SkipLockedClaimStrategy;
import com.dbsync.core.pool.NamedThreadPoolManager;
import com.dbsync.core.checkpoint.CheckpointStore;
import com.dbsync.metadata.dao.CheckpointDao;
import com.dbsync.metadata.dao.DataSourceDao;
import com.dbsync.metadata.dao.ShardDao;
import com.dbsync.metadata.dao.WatermarkDao;
import com.dbsync.core.shard.ShardPlanner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 内核组件装配（数据源注册表 / 抢占策略 / 租约 / 线程池 / 检查点 / 分片规划）。
 *
 * <p>这些对象都是"平台级单例"，绝不能按请求 new：注册表持有连接池，
 * 线程池按任务分桶，检查点写入依赖元数据库事务模板。
 */
@Configuration
public class CoreConfig {

    /**
     * 业务数据源注册表（连接池缓存 + 连接测试）。
     *
     * @param dataSourceDao 数据源 DAO
     * @param cipher        加解密器
     * @return 注册表
     */
    @Bean(destroyMethod = "close")
    public DataSourceRegistry dataSourceRegistry(DataSourceDao dataSourceDao,
                                                 AesGcmCipher cipher) {
        return new DataSourceRegistry(dataSourceDao, cipher);
    }

    /**
     * 分片抢占策略（默认 SKIP LOCKED；DM8 老版本等可用配置切到 NOWAIT_PROBE）。
     *
     * @param shardDao   分片 DAO
     * @param properties 配置
     * @return 策略
     */
    @Bean
    public ShardClaimStrategy shardClaimStrategy(ShardDao shardDao, DbSyncProperties properties) {
        ClaimStrategy configured = DbSyncProperties.ClaimStrategyParser
                .parse(properties.getClaimStrategy());
        switch (configured) {
            case NOWAIT_PROBE:
                return new NoWaitProbeClaimStrategy(shardDao);
            case OPTIMISTIC_CAS:
                return new OptimisticCasClaimStrategy(shardDao);
            case SKIP_LOCKED:
            default:
                return new SkipLockedClaimStrategy(shardDao);
        }
    }

    /**
     * 分片租约管理器。
     *
     * @param shardDao   分片 DAO
     * @param strategy   抢占策略
     * @param properties 配置
     * @return 管理器
     */
    @Bean
    public ShardLeaseManager shardLeaseManager(ShardDao shardDao, ShardClaimStrategy strategy,
                                               DbSyncProperties properties) {
        return new ShardLeaseManager(shardDao, strategy, properties.leaseDuration(),
                properties.leaseHeartbeat());
    }

    /**
     * 按任务命名的线程池管理器。
     *
     * @return 管理器
     */
    @Bean
    public NamedThreadPoolManager namedThreadPoolManager() {
        return new NamedThreadPoolManager();
    }

    /**
     * 断点存储（不变式 I1：checkpoint 必须在 batch commit 之后推进）。
     *
     * @param checkpointDao 检查点 DAO
     * @param shardDao      分片 DAO
     * @param watermarkDao  水位线 DAO
     * @param metaTx        元数据库事务模板
     * @return 存储
     */
    @Bean
    public CheckpointStore checkpointStore(CheckpointDao checkpointDao, ShardDao shardDao,
                                           WatermarkDao watermarkDao,
                                           @Qualifier("metaTransactionTemplate")
                                           TransactionTemplate metaTx) {
        return new CheckpointStore(checkpointDao, shardDao, watermarkDao, metaTx);
    }

    /**
     * 分片规划器（按主键范围切分）。
     *
     * @return 规划器
     */
    @Bean
    public ShardPlanner shardPlanner() {
        return new ShardPlanner();
    }
}
