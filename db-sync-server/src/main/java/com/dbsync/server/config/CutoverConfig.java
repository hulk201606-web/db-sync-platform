package com.dbsync.server.config;

import com.dbsync.core.port.CompareVerdictPort;
import com.dbsync.core.port.IncrementalMetricsPort;
import com.dbsync.cutover.CutoverOrchestrator;
import com.dbsync.cutover.CutoverRecoveryService;
import com.dbsync.cutover.CutoverStateMachine;
import com.dbsync.cutover.GateEvaluator;
import com.dbsync.cutover.RouteRegistry;
import com.dbsync.cutover.RouteRuleEngine;
import com.dbsync.metadata.dao.CutoverPlanDao;
import com.dbsync.metadata.dao.CutoverStepLogDao;
import com.dbsync.metadata.dao.DeadLetterDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 切流装配（T38~T41）。
 *
 * <p>启动时必须跑一次 {@link CutoverRecoveryService}：进程崩溃可能停在
 * "intent 已写、confirmed 未写"的中间态（不变式 I7），重启后要按外部实际形态对齐，
 * 否则前端会看到一个永远不会推进的计划。
 */
@Configuration
public class CutoverConfig {

    private static final Logger log = LoggerFactory.getLogger(CutoverConfig.class);

    /**
     * 切流状态机（9 个状态的唯一跃迁真相）。
     *
     * @return 状态机
     */
    @Bean
    public CutoverStateMachine cutoverStateMachine() {
        return new CutoverStateMachine();
    }

    /**
     * 路由注册表（版本化下发 + ack）。
     *
     * @return 注册表
     */
    @Bean
    public RouteRegistry routeRegistry() {
        return new RouteRegistry();
    }

    /**
     * 灰度规则引擎。
     *
     * @return 引擎
     */
    @Bean
    public RouteRuleEngine routeRuleEngine() {
        return new RouteRuleEngine();
    }

    /**
     * Gate 评估器（4 项指标的服务端唯一判定点）。
     *
     * @param compareVerdictPort 比对结论端口
     * @param incrementalMetricsPort 增量指标端口
     * @param deadLetterDao      死信 DAO
     * @param cutoverPlanDao     计划 DAO
     * @return 评估器
     */
    @Bean
    public GateEvaluator gateEvaluator(CompareVerdictPort compareVerdictPort,
                                       IncrementalMetricsPort incrementalMetricsPort,
                                       DeadLetterDao deadLetterDao, CutoverPlanDao cutoverPlanDao) {
        return new GateEvaluator(compareVerdictPort, incrementalMetricsPort, deadLetterDao,
                cutoverPlanDao);
    }

    /**
     * 切流编排器。
     *
     * @param planDao       计划 DAO
     * @param stepLogDao    步骤 DAO
     * @param stateMachine  状态机
     * @param gateEvaluator Gate 评估器
     * @param routeRegistry 路由注册表
     * @param ruleEngine    规则引擎
     * @param auditService  审计服务（端口实现）
     * @return 编排器
     */
    @Bean
    public CutoverOrchestrator cutoverOrchestrator(CutoverPlanDao planDao,
                                                   CutoverStepLogDao stepLogDao,
                                                   CutoverStateMachine stateMachine,
                                                   GateEvaluator gateEvaluator,
                                                   RouteRegistry routeRegistry,
                                                   RouteRuleEngine ruleEngine,
                                                   com.dbsync.core.port.AuditPort auditService) {
        return new CutoverOrchestrator(planDao, stepLogDao, stateMachine, gateEvaluator,
                routeRegistry, ruleEngine, auditService, CutoverOrchestrator.DEFAULT_ACK_TIMEOUT);
    }

    /**
     * 启动恢复：把 intent/confirmed 不一致的计划按外部形态对齐。
     *
     * @param planDao       计划 DAO
     * @param routeRegistry 路由注册表
     * @return 恢复服务
     */
    @Bean
    public CutoverRecoveryService cutoverRecoveryService(CutoverPlanDao planDao,
                                                         RouteRegistry routeRegistry) {
        return new CutoverRecoveryService(planDao, routeRegistry);
    }

    /**
     * 启动时执行一次恢复扫描。
     *
     * @param recoveryService 恢复服务
     * @return 运行器
     */
    @Bean
    public ApplicationRunner cutoverRecoveryRunner(CutoverRecoveryService recoveryService) {
        return args -> {
            try {
                Object result = recoveryService.reconcileAll();
                log.info("切流启动恢复完成：{}", result);
            } catch (RuntimeException e) {
                // 恢复失败不阻塞启动（平台还能提供其它功能），但必须留下明显日志
                log.error("切流启动恢复失败，请人工检查 cutover_plan 的 intent/confirmed", e);
            }
        };
    }
}
