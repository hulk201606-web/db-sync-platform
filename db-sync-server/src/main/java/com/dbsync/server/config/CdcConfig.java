package com.dbsync.server.config;

import com.dbsync.api.metrics.IncrementalMetricsAdapter;
import com.dbsync.cdc.ChangeQueue;
import com.dbsync.cdc.TriggerInstaller;
import com.dbsync.cdc.WatermarkStore;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.core.port.IncrementalMetricsPort;
import com.dbsync.metadata.dao.ChangeLogDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import com.dbsync.metadata.dao.TriggerDefDao;
import com.dbsync.metadata.dao.WatermarkDao;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 增量（触发器日志）装配（T28~T32）。
 *
 * <p>为什么不在这里装配 {@code ChangeConsumer} / {@code TriggerLogPoller}：
 * 它们是"按任务启停"的运行时长活对象，必须在任务进入 RUNNING 时由
 * {@code MigrationService} 的后续阶段（增量阶段）显式 start、在 PAUSE/CANCEL 时 stop；
 * 提前做成单例 Bean 会变成"没人 start 的空壳"或"任务删了还在跑"。因此本类
 * <b>只装配无状态/共享组件</b>；消费者的按任务启停见 {@link CdcRuntimeConfig}
 * （上下文工厂 + 运行期管理器 + 启动恢复/对账）。
 */
@Configuration
public class CdcConfig {

    /**
     * 触发器部署器（安装/卸载源库触发器）。
     *
     * @param registry        数据源注册表
     * @param syncTaskDao     任务 DAO
     * @param tableMappingDao 表映射 DAO
     * @param triggerDefDao   触发器定义 DAO
     * @return 部署器
     */
    @Bean
    public TriggerInstaller triggerInstaller(DataSourceRegistry registry, SyncTaskDao syncTaskDao,
                                             TableMappingDao tableMappingDao,
                                             TriggerDefDao triggerDefDao) {
        return new TriggerInstaller(registry, syncTaskDao, tableMappingDao, triggerDefDao);
    }

    /**
     * 变更队列（读写 {@code sync_change_log}）。
     *
     * @param changeLogDao 变更日志 DAO
     * @return 队列
     */
    @Bean
    public ChangeQueue changeQueue(ChangeLogDao changeLogDao) {
        return new ChangeQueue(changeLogDao);
    }

    /**
     * 水位线存储（不变式 I4：水位 = MIN(未应用 seq) - 1）。
     *
     * @param watermarkDao 水位线 DAO
     * @param changeLogDao 变更日志 DAO
     * @return 存储
     */
    @Bean
    public WatermarkStore watermarkStore(WatermarkDao watermarkDao, ChangeLogDao changeLogDao) {
        return new WatermarkStore(watermarkDao, changeLogDao);
    }

    /**
     * 增量指标端口实现（给 Gate / 大盘 / 告警取数）。
     *
     * @param watermarkStore 水位线存储
     * @param changeLogDao   变更日志 DAO
     * @param watermarkDao   水位线 DAO
     * @return 端口实现
     */
    @Bean
    public IncrementalMetricsPort incrementalMetricsPort(WatermarkStore watermarkStore,
                                                         ChangeLogDao changeLogDao,
                                                         WatermarkDao watermarkDao) {
        return new IncrementalMetricsAdapter(watermarkStore, changeLogDao, watermarkDao);
    }
}
