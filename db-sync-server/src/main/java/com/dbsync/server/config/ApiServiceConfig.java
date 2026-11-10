package com.dbsync.server.config;

import com.dbsync.api.cdc.CdcRuntimeManager;
import com.dbsync.api.service.CdcService;
import com.dbsync.api.service.CompareService;
import com.dbsync.api.service.CutoverService;
import com.dbsync.api.service.DataSourceService;
import com.dbsync.api.service.DeadLetterAdminService;
import com.dbsync.api.service.MappingService;
import com.dbsync.api.service.MigrationRateLimitService;
import com.dbsync.api.service.MigrationService;
import com.dbsync.api.service.MonitorService;
import com.dbsync.api.service.ShardProgressService;
import com.dbsync.api.service.SdkRouteService;
import com.dbsync.api.service.TaskService;
import com.dbsync.cdc.TriggerInstaller;
import com.dbsync.common.util.AesGcmCipher;
import com.dbsync.common.util.SnowflakeId;
import com.dbsync.compare.CompareEngine;
import com.dbsync.compare.CompareReportBuilder;
import com.dbsync.compare.DiffRepairer;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.core.port.AuditPort;
import com.dbsync.core.port.IncrementalMetricsPort;
import com.dbsync.core.throttle.ThrottleRegistry;
import com.dbsync.cutover.CutoverOrchestrator;
import com.dbsync.cutover.CutoverStateMachine;
import com.dbsync.cutover.GateEvaluator;
import com.dbsync.cutover.RouteRegistry;
import com.dbsync.api.metrics.SyncMetrics;
import com.dbsync.api.alert.AlertService;
import com.dbsync.metadata.dao.ChangeLogDao;
import com.dbsync.metadata.dao.ColumnMappingDao;
import com.dbsync.metadata.dao.CompareBlockDao;
import com.dbsync.metadata.dao.CompareDiffDao;
import com.dbsync.metadata.dao.CompareJobDao;
import com.dbsync.metadata.dao.CutoverPlanDao;
import com.dbsync.metadata.dao.CutoverStepLogDao;
import com.dbsync.metadata.dao.DataSourceDao;
import com.dbsync.metadata.dao.DeadLetterDao;
import com.dbsync.metadata.dao.InstanceDao;
import com.dbsync.metadata.dao.ShardDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import com.dbsync.migration.DeadLetterService;
import com.dbsync.migration.MigrationEngine;
import com.dbsync.migration.MigrationPrecheck;
import com.dbsync.migration.RowCountVerifier;
import java.util.function.Predicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 服务层装配（10 个应用服务）。
 *
 * <p>服务层全部是"构造注入的普通对象"（无 {@code @Service} 注解），
 * 既方便单测直接 new，也让依赖关系在启动时一次性显式化——
 * 装配错误在启动阶段就暴露，而不是等到某个接口第一次被调用。
 */
@Configuration
public class ApiServiceConfig {

    /**
     * 数据源服务。
     *
     * @param dataSourceDao 数据源 DAO
     * @param syncTaskDao   任务 DAO
     * @param registry      注册表
     * @param cipher        加解密器
     * @param snowflakeId   雪花 ID
     * @return 服务
     */
    @Bean
    public DataSourceService dataSourceService(DataSourceDao dataSourceDao, SyncTaskDao syncTaskDao,
                                               DataSourceRegistry registry, AesGcmCipher cipher,
                                               SnowflakeId snowflakeId) {
        return new DataSourceService(dataSourceDao, syncTaskDao, registry, cipher, snowflakeId);
    }

    /**
     * 任务服务。
     *
     * @param syncTaskDao     任务 DAO
     * @param dataSourceDao   数据源 DAO
     * @param tableMappingDao 表映射 DAO
     * @param snowflakeId     雪花 ID
     * @param cdcRuntime      增量消费运行期
     * @return 服务
     */
    @Bean
    public TaskService taskService(SyncTaskDao syncTaskDao, DataSourceDao dataSourceDao,
                                   TableMappingDao tableMappingDao, SnowflakeId snowflakeId,
                                   CdcRuntimeManager cdcRuntime) {
        return new TaskService(syncTaskDao, dataSourceDao, tableMappingDao, snowflakeId,
                cdcRuntime);
    }

    /**
     * 映射服务（自动生成映射 + DDL）。
     *
     * @param syncTaskDao      任务 DAO
     * @param dataSourceDao    数据源 DAO
     * @param tableMappingDao  表映射 DAO
     * @param columnMappingDao 列映射 DAO
     * @param registry         注册表
     * @param snowflakeId      雪花 ID
     * @return 服务
     */
    @Bean
    public MappingService mappingService(SyncTaskDao syncTaskDao, DataSourceDao dataSourceDao,
                                         TableMappingDao tableMappingDao,
                                         ColumnMappingDao columnMappingDao,
                                         DataSourceRegistry registry, SnowflakeId snowflakeId) {
        return new MappingService(syncTaskDao, dataSourceDao, tableMappingDao, columnMappingDao,
                registry, snowflakeId);
    }

    /**
     * 迁移服务。
     *
     * @param migrationEngine 迁移引擎
     * @param precheck        预检器
     * @param rowCountVerifier 核数器
     * @param taskService     任务服务
     * @param cdcRuntime      增量消费运行期
     * @return 服务
     */
    @Bean
    public MigrationService migrationService(MigrationEngine migrationEngine,
                                             MigrationPrecheck precheck,
                                             RowCountVerifier rowCountVerifier,
                                             TaskService taskService,
                                             CdcRuntimeManager cdcRuntime) {
        return new MigrationService(migrationEngine, precheck, rowCountVerifier, taskService,
                cdcRuntime);
    }

    /**
     * 运行中动态限速服务（MIG-05）。
     *
     * @param syncTaskDao      任务 DAO（落库 rate_limit_rows）
     * @param taskService      任务服务（存在性校验）
     * @param throttleRegistry 限速器注册表（与 worker 共用同一实例）
     * @return 服务
     */
    @Bean
    public MigrationRateLimitService migrationRateLimitService(SyncTaskDao syncTaskDao,
                                                               TaskService taskService,
                                                               ThrottleRegistry throttleRegistry) {
        return new MigrationRateLimitService(syncTaskDao, taskService, throttleRegistry);
    }

    /**
     * 表级 / 分片级进度服务（MIG-06）。
     *
     * @param shardDao        分片 DAO
     * @param tableMappingDao 表映射 DAO
     * @param taskService     任务服务（存在性校验）
     * @return 服务
     */
    @Bean
    public ShardProgressService shardProgressService(ShardDao shardDao,
                                                     TableMappingDao tableMappingDao,
                                                     TaskService taskService) {
        return new ShardProgressService(shardDao, tableMappingDao, taskService);
    }

    /**
     * 增量服务。
     *
     * @param syncTaskDao      任务 DAO
     * @param tableMappingDao  表映射 DAO
     * @param changeLogDao     变更日志 DAO
     * @param triggerInstaller 触发器部署器
     * @param metricsPort      增量指标端口
     * @param taskService      任务服务
     * @param cdcRuntime       增量消费运行期
     * @return 服务
     */
    @Bean
    public CdcService cdcService(SyncTaskDao syncTaskDao, TableMappingDao tableMappingDao,
                                 ChangeLogDao changeLogDao, TriggerInstaller triggerInstaller,
                                 IncrementalMetricsPort metricsPort, TaskService taskService,
                                 CdcRuntimeManager cdcRuntime) {
        return new CdcService(syncTaskDao, tableMappingDao, changeLogDao, triggerInstaller,
                metricsPort, taskService, cdcRuntime);
    }

    /**
     * 比对服务。
     *
     * @param compareEngine  比对引擎
     * @param compareJobDao  作业 DAO
     * @param compareDiffDao 差异 DAO
     * @param compareBlockDao 块 DAO
     * @param diffRepairer   修复器
     * @param reportBuilder  报告生成器
     * @param syncTaskDao    任务 DAO
     * @param adminChecker   ADMIN 判定器
     * @return 服务
     */
    @Bean
    public CompareService compareService(CompareEngine compareEngine, CompareJobDao compareJobDao,
                                         CompareDiffDao compareDiffDao,
                                         CompareBlockDao compareBlockDao,
                                         DiffRepairer diffRepairer,
                                         CompareReportBuilder reportBuilder,
                                         SyncTaskDao syncTaskDao,
                                         Predicate<String> adminChecker) {
        return new CompareService(compareEngine, compareJobDao, compareDiffDao, compareBlockDao,
                diffRepairer, reportBuilder, syncTaskDao, adminChecker);
    }

    /**
     * 切流服务。
     *
     * @param planDao       计划 DAO
     * @param stepLogDao    步骤 DAO
     * @param orchestrator  编排器
     * @param gateEvaluator Gate 评估器
     * @param stateMachine  状态机
     * @param routeRegistry 路由注册表
     * @param syncTaskDao   任务 DAO
     * @param dataSourceDao 数据源 DAO
     * @param auditPort     审计端口
     * @param snowflakeId   雪花 ID
     * @return 服务
     */
    @Bean
    public CutoverService cutoverService(CutoverPlanDao planDao, CutoverStepLogDao stepLogDao,
                                         CutoverOrchestrator orchestrator,
                                         GateEvaluator gateEvaluator,
                                         CutoverStateMachine stateMachine,
                                         RouteRegistry routeRegistry, SyncTaskDao syncTaskDao,
                                         DataSourceDao dataSourceDao, AuditPort auditPort,
                                         SnowflakeId snowflakeId) {
        return new CutoverService(planDao, stepLogDao, orchestrator, gateEvaluator, stateMachine,
                routeRegistry, syncTaskDao, dataSourceDao, auditPort, snowflakeId);
    }

    /**
     * SDK 路由服务（PRD CUT-01：把已确认状态下的路由快照下发给业务侧 SDK）。
     *
     * <p>// ADDED：CUT-01 原先只有"内部发布快照"（{@link RouteRegistry}），
     * 缺一条把快照真正送到业务侧的通道；本服务 + {@code SdkRouteController}
     * 补齐 {@code /api/sdk/route-snapshot} 与 {@code /api/sdk/route-ack}。
     *
     * @param planDao       计划 DAO（读 confirmed_state / route_version / acked_version）
     * @param routeRuleEngine 规则引擎（快照计算的唯一真相）
     * @param routeRegistry 内存路由注册表（ack 尽力转发以解除编排器等待）
     * @return 服务
     */
    @Bean
    public SdkRouteService sdkRouteService(CutoverPlanDao planDao,
                                           com.dbsync.cutover.RouteRuleEngine routeRuleEngine,
                                           RouteRegistry routeRegistry) {
        return new SdkRouteService(planDao, routeRuleEngine, routeRegistry);
    }

    /**
     * 死信管理服务（重放/丢弃）。
     *
     * @param deadLetterDao     死信 DAO
     * @param deadLetterService 死信服务
     * @return 服务
     */
    @Bean
    public DeadLetterAdminService deadLetterAdminService(DeadLetterDao deadLetterDao,
                                                         DeadLetterService deadLetterService) {
        return new DeadLetterAdminService(deadLetterDao, deadLetterService);
    }

    /**
     * 大盘服务。
     *
     * @param syncTaskDao    任务 DAO
     * @param shardDao       分片 DAO
     * @param deadLetterDao  死信 DAO
     * @param cutoverPlanDao 计划 DAO
     * @param instanceDao    实例 DAO
     * @param syncMetrics    指标
     * @param alertService   告警服务
     * @return 服务
     */
    @Bean
    public MonitorService monitorService(SyncTaskDao syncTaskDao, ShardDao shardDao,
                                         DeadLetterDao deadLetterDao, CutoverPlanDao cutoverPlanDao,
                                         InstanceDao instanceDao, SyncMetrics syncMetrics,
                                         AlertService alertService) {
        return new MonitorService(syncTaskDao, shardDao, deadLetterDao, cutoverPlanDao, instanceDao,
                syncMetrics, alertService);
    }
}
