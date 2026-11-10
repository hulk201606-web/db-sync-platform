package com.dbsync.cutover;

import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.enums.RouteTarget;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.exception.IllegalStateTransitionException;
import com.dbsync.common.model.CutoverPlan;
import com.dbsync.common.model.CutoverStep;
import com.dbsync.common.model.GateResult;
import com.dbsync.common.model.RouteRule;
import com.dbsync.common.model.RouteSnapshot;
import com.dbsync.common.util.JsonUtil;
import com.dbsync.core.port.AuditEntry;
import com.dbsync.core.port.AuditPort;
import com.dbsync.common.enums.AuditResult;
import com.dbsync.metadata.dao.CutoverPlanDao;
import com.dbsync.metadata.dao.CutoverStepLogDao;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 切流编排器（T40）：两段式推进 + 一键回滚 + dry-run。
 *
 * <p><b>推进六步</b>（架构 §4.5.1 / §8.3）：
 * <ol>
 *   <li>状态校验（{@link CutoverStateMachine}），并强制 I7：{@code intent != confirmed} 时拒绝推进；</li>
 *   <li>若当前是 {@code PRE_CHECK} → 跑 Gate（4 指标）；不通过则落 {@code PRE_CHECK_FAILED} 并留痕；</li>
 *   <li>写 {@code intent}（第 1 段，{@code plan_status=INCONSISTENT}）；</li>
 *   <li>下发路由快照（{@code routeVersion} 单调递增）；</li>
 *   <li>等 ack（默认 30s）—— 超时由 {@link StepCompensator} 回退 intent 并落 {@code TIMEOUT}；</li>
 *   <li>写 {@code confirmed} + {@code route_version}（第 2 段），落步骤留痕。</li>
 * </ol>
 *
 * <p><b>回滚</b>（架构 §4.5.5）：<b>单个</b>快照原子切换（{@code read=SOURCE 100%} 且
 * {@code writeTarget=false} 在同一次 publish 内生效），可跳步（{@code GRAY_WRITE → ROLLED_BACK}）。
 * 分两步发（先停写再改读）会制造"读已回源、写还在目标"的窗口，那段时间的写入会丢在目标库。
 */
public class CutoverOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(CutoverOrchestrator.class);

    /** 默认 ack 超时（架构 §4.6.4：30s 未 ack 必须回退）。 */
    public static final Duration DEFAULT_ACK_TIMEOUT = Duration.ofSeconds(30L);

    /** 灰度初始比例（第一次进入 GRAY_READ / GRAY_WRITE 时下发）。 */
    private static final int INITIAL_GRAY_RATIO = 5;

    private final CutoverPlanDao planDao;
    private final CutoverStepLogDao stepLogDao;
    private final CutoverStateMachine stateMachine;
    private final GateEvaluator gateEvaluator;
    private final RouteRegistry routeRegistry;
    private final StepCompensator compensator;
    private final RouteRuleEngine ruleEngine;
    private final AuditPort auditPort;
    private final Duration ackTimeout;

    /**
     * 构造（生产装配用默认 30s ack 超时、无审计端口）。
     *
     * @param planDao       计划 DAO
     * @param stepLogDao    步骤留痕 DAO
     * @param stateMachine  状态机
     * @param gateEvaluator Gate 评估器
     * @param routeRegistry 路由注册表
     */
    public CutoverOrchestrator(CutoverPlanDao planDao, CutoverStepLogDao stepLogDao,
                               CutoverStateMachine stateMachine, GateEvaluator gateEvaluator,
                               RouteRegistry routeRegistry) {
        this(planDao, stepLogDao, stateMachine, gateEvaluator, routeRegistry,
                new RouteRuleEngine(), null, DEFAULT_ACK_TIMEOUT);
    }

    /**
     * 构造（全参数）。
     *
     * @param planDao       计划 DAO
     * @param stepLogDao    步骤留痕 DAO
     * @param stateMachine  状态机
     * @param gateEvaluator Gate 评估器
     * @param routeRegistry 路由注册表
     * @param ruleEngine    路由规则引擎
     * @param auditPort     审计端口（可 null）
     * @param ackTimeout    ack 超时（null 取 30s）
     */
    public CutoverOrchestrator(CutoverPlanDao planDao, CutoverStepLogDao stepLogDao,
                               CutoverStateMachine stateMachine, GateEvaluator gateEvaluator,
                               RouteRegistry routeRegistry, RouteRuleEngine ruleEngine,
                               AuditPort auditPort, Duration ackTimeout) {
        this.planDao = planDao;
        this.stepLogDao = stepLogDao;
        this.stateMachine = stateMachine;
        this.gateEvaluator = gateEvaluator;
        this.routeRegistry = routeRegistry;
        this.ruleEngine = ruleEngine == null ? new RouteRuleEngine() : ruleEngine;
        this.auditPort = auditPort;
        this.compensator = new StepCompensator(planDao, stepLogDao);
        this.ackTimeout = ackTimeout == null ? DEFAULT_ACK_TIMEOUT : ackTimeout;
    }

    // ------------------------------------------------------------------
    // 推进
    // ------------------------------------------------------------------

    /**
     * 推进一步。
     *
     * @param planId   计划 ID
     * @param operator 操作人
     * @return 步骤留痕
     * @throws IllegalStateTransitionException {@code E6001} 非法跃迁
     * @throws DbSyncException {@code E6004} intent/confirmed 不一致（I7）、{@code E3005} 版本冲突
     */
    public CutoverStep advance(long planId, String operator) {
        return advanceInternal(planId, operator, false, null);
    }

    /**
     * 跳卡点推进（skip-gate，三件套校验在 api 层完成，见 T41）。
     *
     * <p>// ADDED: 04 文档 T41 要求"跳过 Gate"能力，而 {@link #advance(long, String)} 的签名
     * （文档 T40 冻结）不允许带跳过标记。这里加一个<b>重载</b>而不是改原签名，
     * 保证已有调用方与 {@code OrchestratorTest} 的断言全部不变。
     *
     * <p>语义：仍然评估 Gate 并留痕（审计要"跳过时的实测值"），但<b>不阻断</b>，
     * 步骤留痕里 {@code gateSkipped=true} 且记录理由。
     *
     * @param planId     计划 ID
     * @param operator   操作人
     * @param skipReason 跳过理由（≥10 字，由调用方校验）
     * @return 步骤留痕
     */
    public CutoverStep advanceSkippingGate(long planId, String operator, String skipReason) {
        if (skipReason == null || skipReason.trim().isEmpty()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "跳过 Gate 必须填写理由");
        }
        return advanceInternal(planId, operator, true, skipReason);
    }

    /**
     * 推进一步（内部实现）。
     *
     * @param planId     计划 ID
     * @param operator   操作人
     * @param skipGate   是否跳过 Gate 阻断
     * @param skipReason 跳过理由（skipGate=false 时为 null）
     * @return 步骤留痕
     */
    private CutoverStep advanceInternal(long planId, String operator, boolean skipGate,
                                       String skipReason) {
        CutoverPlan plan = load(planId);
        requireConsistent(plan);
        CutoverState from = plan.getIntentState();
        long startedAt = System.currentTimeMillis();
        long versionBefore = plan.getRouteVersion();
        int stepSeq = stepLogDao.nextStepSeq(planId);

        // ② Gate：只有 PRE_CHECK 出口需要判卡点
        GateResult gate = null;
        CutoverState to;
        if (from == CutoverState.PRE_CHECK) {
            gate = gateEvaluator.evaluate(planId);
            planDao.updateGateSnapshot(planId, JsonUtil.toJson(gate.getReasons()));
            to = gate.isPass() || skipGate ? CutoverState.GRAY_READ : CutoverState.PRE_CHECK_FAILED;
        } else {
            to = stateMachine.nextForward(from);
            if (to == null) {
                throw new IllegalStateTransitionException(from, from, from.allowedNext());
            }
        }
        stateMachine.assertTransition(from, to);

        // Gate 不通过：只需把 intent/confirmed 一起落到 PRE_CHECK_FAILED（外部无动作）
        if (gate != null && !gate.isPass() && !skipGate) {
            CutoverPlan fresh = load(planId);
            planDao.updateIntent(planId, CutoverState.PRE_CHECK_FAILED,
                    planDao.versionOf(planId));
            CutoverPlan afterIntent = load(planId);
            planDao.confirm(planId, CutoverState.PRE_CHECK_FAILED, afterIntent.getRouteVersion(),
                    planDao.versionOf(planId));
            String reason = String.join("；", gate.getReasons());
            recordAudit(plan, "CUTOVER_ADVANCE", AuditResult.FAILED, operator, reason);
            return compensator.recordBlocked(plan, from, CutoverState.PRE_CHECK_FAILED, operator,
                    stepSeq, startedAt, gate, reason);
        }

        // ③ 写 intent（第 1 段）
        CutoverPlan fresh = load(planId);
        if (planDao.updateIntent(planId, to, planDao.versionOf(planId)) == 0) {
            throw new DbSyncException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                    "计划已被其他操作修改，请刷新后重试 plan=" + planId);
        }
        // 灰度比例随状态推进
        applyGrayRatio(planId, to);

        // ④ 下发快照
        RouteSnapshot snapshot = ruleEngine.snapshotFor(plan, to, 0L, Instant.now());
        long publishedVersion = routeRegistry.publish(planId, snapshot);

        // ⑤ 等 ack（超时 → 补偿回退，K5 门禁）
        boolean acked = routeRegistry.awaitAck(planId, publishedVersion, ackTimeout);
        if (!acked) {
            recordAudit(plan, "CUTOVER_ADVANCE", AuditResult.FAILED, operator,
                    StepCompensator.ERROR_CONFIRM_TIMEOUT);
            return compensator.compensateAckTimeout(plan, to, from, publishedVersion, operator,
                    stepSeq, startedAt);
        }

        // ⑥ 写 confirmed（第 2 段）
        CutoverPlan afterIntent = load(planId);
        if (planDao.confirm(planId, to, publishedVersion, planDao.versionOf(planId)) == 0) {
            throw new DbSyncException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                    "写 confirmed 冲突，状态待对齐 plan=" + planId);
        }
        if (to == CutoverState.OBSERVE) {
            planDao.startObserve(planId);
        }
        long duration = Math.max(0L, System.currentTimeMillis() - startedAt);
        CutoverStep step = new CutoverStep(planId, stepSeq, from, to, "ADVANCE", "SUCCESS", null,
                duration, operator, versionBefore, publishedVersion, skipGate, skipReason, gate);
        stepLogDao.append(step, null);
        recordAudit(plan, "CUTOVER_ADVANCE", AuditResult.SUCCESS, operator, from + "→" + to
                + (skipGate ? "（跳过 Gate：" + skipReason + "）" : ""));
        log.info("切流推进成功 plan={} {}→{} version={} 耗时={}ms 跳过Gate={}", planId, from, to,
                publishedVersion, duration, Boolean.valueOf(skipGate));
        return step;
    }

    // ------------------------------------------------------------------
    // 回滚
    // ------------------------------------------------------------------

    /**
     * 一键回滚（单快照原子切换，可跳步）。
     *
     * @param planId   计划 ID
     * @param operator 操作人
     * @param reason   原因（必填，落审计与计划）
     * @return 步骤留痕
     * @throws IllegalStateTransitionException {@code E6001} 当前状态不允许回滚
     */
    public CutoverStep rollback(long planId, String operator, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "回滚必须填写原因（reason）");
        }
        CutoverPlan plan = load(planId);
        CutoverState from = plan.getIntentState();
        stateMachine.assertRollback(from);

        long startedAt = System.currentTimeMillis();
        long versionBefore = plan.getRouteVersion();
        int stepSeq = stepLogDao.nextStepSeq(planId);

        CutoverPlan fresh = load(planId);
        if (planDao.updateIntent(planId, CutoverState.ROLLED_BACK, planDao.versionOf(planId)) == 0) {
            throw new DbSyncException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                    "回滚遇版本冲突，请刷新后重试 plan=" + planId);
        }
        // 硬约束：读回源 + 停写目标必须在**同一次** publish 里（避免中间窗口丢写）
        RouteSnapshot rollbackSnapshot = new RouteSnapshot(planId, 0L, RouteTarget.SOURCE, 100,
                false, 0, List.<RouteRule>of(ruleEngine.fallbackToSource()), Instant.now());
        long publishedVersion = routeRegistry.publish(planId, rollbackSnapshot);
        boolean acked = routeRegistry.awaitAck(planId, publishedVersion, ackTimeout);
        if (!acked) {
            recordAudit(plan, "CUTOVER_ROLLBACK", AuditResult.FAILED, operator,
                    StepCompensator.ERROR_CONFIRM_TIMEOUT);
            return compensator.compensateAckTimeout(plan, CutoverState.ROLLED_BACK, from,
                    publishedVersion, operator, stepSeq, startedAt);
        }
        CutoverPlan afterIntent = load(planId);
        planDao.confirm(planId, CutoverState.ROLLED_BACK, publishedVersion,
                planDao.versionOf(planId));
        planDao.markRolledBack(planId, reason);
        planDao.updatePlanStatus(planId, CutoverPlanDao.STATUS_FROZEN);

        long duration = Math.max(0L, System.currentTimeMillis() - startedAt);
        CutoverStep step = new CutoverStep(planId, stepSeq, from, CutoverState.ROLLED_BACK,
                "ROLLBACK", "SUCCESS", null, duration, operator, versionBefore, publishedVersion,
                false, reason, null);
        stepLogDao.append(step, null);
        recordAudit(plan, "CUTOVER_ROLLBACK", AuditResult.SUCCESS, operator, reason);
        log.warn("切流已回滚 plan={} {}→ROLLED_BACK version={} 耗时={}ms 原因={}", planId, from,
                publishedVersion, duration, reason);
        return step;
    }

    // ------------------------------------------------------------------
    // dry-run / Gate
    // ------------------------------------------------------------------

    /**
     * 只读演练：列出"如果现在推进一步会发生什么"，<b>不写任何状态</b>。
     *
     * <p>{@code // TODO(P1)} 真正的"沙箱环境演练"（在隔离环境真实下发并观测影响面）
     * 属 P1，本期只做步骤预演（T41 的"接口预留（简化）"）。
     *
     * @param planId 计划 ID
     * @return 演练报告
     */
    public DryRunReport dryRun(long planId) {
        CutoverPlan plan = load(planId);
        CutoverState from = plan.getIntentState();
        GateResult gate = from == CutoverState.PRE_CHECK ? gateEvaluator.evaluate(planId) : null;
        CutoverState to = from == CutoverState.PRE_CHECK
                ? (gate != null && gate.isPass() ? CutoverState.GRAY_READ
                        : CutoverState.PRE_CHECK_FAILED)
                : stateMachine.nextForward(from);
        boolean allowed = to != null && stateMachine.canTransition(from, to)
                && (gate == null || gate.isPass())
                && plan.getIntentState() == plan.getConfirmedState();

        List<String> steps = new ArrayList<String>();
        steps.add("1. 校验状态跃迁 " + from + " → " + to);
        if (gate != null) {
            steps.add("2. 评估 Gate 四指标：" + (gate.isPass() ? "通过" : "阻断（" 
                    + String.join("；", gate.getReasons()) + "）"));
        } else {
            steps.add("2. 无需 Gate（仅 PRE_CHECK 出口判定）");
        }
        if (allowed) {
            steps.add("3. 写 intent_state=" + to + "（plan_status=INCONSISTENT）");
            steps.add("4. 下发路由快照，route_version=" + (plan.getRouteVersion() + 1));
            steps.add("5. 等待 SDK ack（" + ackTimeout.toMillis() + "ms），超时则回退 intent");
            steps.add("6. 写 confirmed_state=" + to + " 并落步骤留痕");
        } else {
            steps.add("3. 本次不会执行任何写操作（不允许推进）");
        }
        long nextVersion = allowed ? plan.getRouteVersion() + 1L : 0L;
        log.info("切流 dry-run plan={} {}→{} allowed={}", planId, from, to, allowed);
        return new DryRunReport(planId, from.name(), to == null ? from.name() : to.name(), allowed,
                gate, nextVersion, steps, Instant.now());
    }

    /**
     * 单独评估 Gate（API 的 {@code POST /plans/{id}/gate}）。
     *
     * @param planId 计划 ID
     * @return 判定结果
     */
    public GateResult evaluateGate(long planId) {
        GateResult result = gateEvaluator.evaluate(planId);
        planDao.updateGateSnapshot(planId, JsonUtil.toJson(result.getReasons()));
        return result;
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 读计划（不存在抛 {@code E1001}）。
     *
     * @param planId 计划 ID
     * @return 计划
     */
    private CutoverPlan load(long planId) {
        return planDao.findById(planId).orElseThrow(() -> new DbSyncException(
                ErrorCode.PARAM_INVALID, "切流计划不存在：" + planId));
    }

    /**
     * I7：intent 与 confirmed 不一致时禁止推进。
     *
     * @param plan 计划
     * @throws DbSyncException {@code E6004}
     */
    private void requireConsistent(CutoverPlan plan) {
        if (plan.getIntentState() != plan.getConfirmedState()) {
            throw new DbSyncException(ErrorCode.STATE_INCONSISTENT,
                    "intent=" + plan.getIntentState() + " 与 confirmed="
                            + plan.getConfirmedState() + " 不一致，必须先对齐再推进（I7）");
        }
    }

    /**
     * 按目标状态推进灰度比例。
     *
     * @param planId 计划 ID
     * @param to     目标状态
     */
    private void applyGrayRatio(long planId, CutoverState to) {
        switch (to) {
            case GRAY_READ:
                planDao.updateGray(planId, INITIAL_GRAY_RATIO, 0);
                break;
            case GRAY_WRITE:
                planDao.updateGray(planId, 100, INITIAL_GRAY_RATIO);
                break;
            case FULL_TARGET:
            case OBSERVE:
            case DONE:
                planDao.updateGray(planId, 100, 100);
                break;
            default:
                break;
        }
    }

    /**
     * 记审计（审计端口约定"永不抛异常"，这里再兜一层）。
     *
     * @param plan     计划
     * @param action   动作
     * @param result   结果
     * @param operator 操作人
     * @param detail   详情
     */
    private void recordAudit(CutoverPlan plan, String action, AuditResult result, String operator,
                             String detail) {
        if (auditPort == null) {
            return;
        }
        try {
            auditPort.record(new AuditEntry(operator, "ADMIN", action, "CUTOVER_PLAN",
                    Long.valueOf(plan.getId()), plan.getPlanNo(), null, result, null, null, null,
                    null, Instant.now(), java.util.Map.of("detail", String.valueOf(detail))));
        } catch (RuntimeException e) {
            log.warn("审计写入失败（不影响切流动作）：{}", e.getMessage());
        }
    }
}
