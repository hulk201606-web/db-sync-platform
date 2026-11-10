package com.dbsync.cutover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.enums.GateItemCode;
import com.dbsync.common.model.CompareVerdictSnapshot;
import com.dbsync.common.model.CutoverStep;
import com.dbsync.common.model.GateResult;
import com.dbsync.core.port.CompareVerdictPort;
import com.dbsync.core.port.IncrementalMetricsPort;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T40 验收：编排器。
 *
 * <p>核心是两条：<b>ack 超时必须回退 intent</b>（架构 §4.6.4 J1 / 坑 K5），
 * 以及"每步都写 step_log（含 gate_json 与 route_version 前后值）"。
 */
class OrchestratorTest extends CutoverTestSupport {

    /** 极短的 ack 超时：让"SDK 不 ack"的用例秒级完成。 */
    private static final Duration SHORT_ACK = Duration.ofMillis(250L);

    /**
     * 全通过的 Gate 评估器。
     *
     * @return 评估器
     */
    private GateEvaluator passGate() {
        CompareVerdictPort cmp = taskId -> Optional.of(new CompareVerdictSnapshot(1L, taskId,
                1.0d, 100L, 0L, "PASS", Instant.now().minusSeconds(60L), java.util.Map.of()));
        IncrementalMetricsPort metrics = new IncrementalMetricsPort() {
            @Override
            public long latencyP95(long taskId) {
                return 100L;
            }

            @Override
            public long backlog(long taskId) {
                return 0L;
            }

            @Override
            public double failRate(long taskId, Duration window) {
                return 0.0d;
            }

            @Override
            public Instant lastApplyAt(long taskId) {
                return Instant.now();
            }
        };
        return new GateEvaluator(cmp, metrics, deadLetterDao, planDao);
    }

    /**
     * 全阻断的 Gate 评估器（无比对结论）。
     *
     * @return 评估器
     */
    private GateEvaluator blockGate() {
        CompareVerdictPort cmp = taskId -> Optional.<CompareVerdictSnapshot>empty();
        IncrementalMetricsPort metrics = new IncrementalMetricsPort() {
            @Override
            public long latencyP95(long taskId) {
                return 100L;
            }

            @Override
            public long backlog(long taskId) {
                return 0L;
            }

            @Override
            public double failRate(long taskId, Duration window) {
                return 0.0d;
            }

            @Override
            public Instant lastApplyAt(long taskId) {
                return Instant.now();
            }
        };
        return new GateEvaluator(cmp, metrics, deadLetterDao, planDao);
    }

    /**
     * 装配编排器。
     *
     * @param gate       Gate 评估器
     * @param ackTimeout ack 超时
     * @return 编排器
     */
    private CutoverOrchestrator orchestrator(GateEvaluator gate, Duration ackTimeout) {
        return new CutoverOrchestrator(planDao, stepLogDao, new CutoverStateMachine(), gate,
                routeRegistry, new RouteRuleEngine(), null, ackTimeout);
    }

    @Test
    @DisplayName("should_advanceToPreCheck_When_draft：DRAFT→PRE_CHECK 不需 Gate")
    void should_advanceToPreCheck_When_draft() {
        long planId = newPlan(CutoverState.DRAFT, CutoverState.DRAFT);
        try (FakeSdk sdk = new FakeSdk(routeRegistry, planId, 5L)) {
            CutoverStep step = orchestrator(passGate(), Duration.ofSeconds(2L)).advance(planId,
                    "alice");
            assertEquals("SUCCESS", step.getResult());
        }
        assertEquals(CutoverState.PRE_CHECK, plan(planId).getIntentState());
        assertEquals(CutoverState.PRE_CHECK, plan(planId).getConfirmedState());
        assertEquals(1, stepLogDao.findByPlan(planId).size());
    }

    @Test
    @DisplayName("should_block_When_gateFails：Gate 不通过落 PRE_CHECK_FAILED 并留痕")
    void should_block_When_gateFails() {
        long planId = newPlan(CutoverState.PRE_CHECK, CutoverState.PRE_CHECK);
        try (FakeSdk sdk = new FakeSdk(routeRegistry, planId, 5L)) {
            CutoverStep step = orchestrator(blockGate(), Duration.ofSeconds(2L)).advance(planId,
                    "alice");
            assertEquals("BLOCKED", step.getResult());
            assertTrue(step.getErrorMsg().contains("无有效比对结论"), step.getErrorMsg());
        }
        CutoverPlanHolder holder = holder(planId);
        assertEquals(CutoverState.PRE_CHECK_FAILED, holder.intent);
        assertEquals(CutoverState.PRE_CHECK_FAILED, holder.confirmed);
        assertEquals(0L, holder.routeVersion, "被阻断时不得下发新路由");
    }

    @Test
    @DisplayName("should_rollbackIntent_When_ackTimeout：ack 超时后 intent 回到原值且留 TIMEOUT")
    void should_rollbackIntent_When_ackTimeout() {
        long planId = newPlan(CutoverState.PRE_CHECK, CutoverState.PRE_CHECK);
        // 故意不启动 FakeSdk：模拟 SDK/网关不 ack
        CutoverOrchestrator orchestrator = orchestrator(passGate(), SHORT_ACK);

        CutoverStep step = orchestrator.advance(planId, "alice");

        assertEquals("TIMEOUT", step.getResult(), "必须落 TIMEOUT 结果");
        assertTrue(step.getErrorMsg().contains("CONFIRM_TIMEOUT"), step.getErrorMsg());
        CutoverPlanHolder holder = holder(planId);
        assertEquals(CutoverState.PRE_CHECK, holder.intent, "intent 必须回退到原值");
        assertEquals(CutoverState.PRE_CHECK, holder.confirmed, "I5：intent 必须等于 confirmed");
        assertEquals(0L, holder.routeVersion, "confirmed 未被推进（route_version 仍为 0）");
        assertEquals(1, stepLogDao.findByPlan(planId).size());
        assertEquals("TIMEOUT", stepLogDao.lastStep(planId).getResult());
    }

    @Test
    @DisplayName("should_refuseAdvance_When_intentNotConfirmed：I7 不一致时拒绝推进")
    void should_refuseAdvance_When_intentNotConfirmed() {
        long planId = newPlan(CutoverState.GRAY_WRITE, CutoverState.GRAY_READ);
        org.junit.jupiter.api.Assertions.assertThrows(
                com.dbsync.common.exception.DbSyncException.class,
                () -> orchestrator(passGate(), SHORT_ACK).advance(planId, "alice"));
    }

    @Test
    @DisplayName("should_writeGateJson_When_advanceThroughPreCheck：step_log 带 gate 明细")
    void should_writeGateJson_When_advanceThroughPreCheck() {
        long planId = newPlan(CutoverState.PRE_CHECK, CutoverState.PRE_CHECK);
        try (FakeSdk sdk = new FakeSdk(routeRegistry, planId, 5L)) {
            CutoverStep step = orchestrator(passGate(), Duration.ofSeconds(2L)).advance(planId,
                    "alice");
            assertTrue(step.isSuccess());
            assertEquals(CutoverState.GRAY_READ, step.getToState());
            GateResult gate = step.getGateResult();
            assertTrue(gate != null && gate.isPass());
        }
        String gateJson = stepLogDao.gateJsonOf(planId, 1);
        assertTrue(gateJson != null && gateJson.contains("COMPARE_PASS_RATE"), gateJson);
        assertEquals(1L, plan(planId).getRouteVersion());
    }

    @Test
    @DisplayName("should_listStepsWithoutWriting_When_dryRun：dry-run 不写任何状态")
    void should_listStepsWithoutWriting_When_dryRun() {
        long planId = newPlan(CutoverState.GRAY_READ, CutoverState.GRAY_READ);
        CutoverOrchestrator orchestrator = orchestrator(passGate(), SHORT_ACK);
        CutoverPlanHolder before = holder(planId);

        DryRunReport report = orchestrator.dryRun(planId);

        assertTrue(report.isAllowed());
        assertEquals("GRAY_READ", report.getCurrentState());
        assertEquals("GRAY_WRITE", report.getTargetState());
        assertTrue(report.getSteps().size() >= 4);
        assertTrue(report.getNextRouteVersion() > 0L);
        CutoverPlanHolder after = holder(planId);
        assertEquals(before.intent, after.intent, "dry-run 不得改 intent");
        assertEquals(before.confirmed, after.confirmed);
        assertEquals(before.routeVersion, after.routeVersion);
        assertEquals(0, stepLogDao.findByPlan(planId).size(), "dry-run 不得写 step_log");
    }

    @Test
    @DisplayName("should_exposeFourItems_When_evaluateGate：单独评估 Gate 并落快照")
    void should_exposeFourItems_When_evaluateGate() {
        long planId = newPlan(CutoverState.PRE_CHECK, CutoverState.PRE_CHECK);
        GateResult result = orchestrator(passGate(), SHORT_ACK).evaluateGate(planId);
        assertTrue(result.isPass());
        assertEquals(4, result.getItems().size());
        boolean hasCompare = false;
        for (GateResult.GateItem item : result.getItems()) {
            hasCompare |= item.getCode() == GateItemCode.COMPARE_PASS_RATE;
        }
        assertTrue(hasCompare);
        assertFalse(jdbc.queryForObject("SELECT gate_snapshot_json FROM cutover_plan WHERE id=?",
                String.class, Long.valueOf(planId)).isEmpty());
    }

    /**
     * 读计划 triple。
     *
     * @param planId 计划 ID
     * @return 轻量视图
     */
    private CutoverPlanHolder holder(long planId) {
        return jdbc.queryForObject("SELECT intent_state,confirmed_state,route_version "
                        + "FROM cutover_plan WHERE id=?",
                (rs, rowNum) -> new CutoverPlanHolder(CutoverState.valueOf(rs.getString(1)),
                        CutoverState.valueOf(rs.getString(2)), rs.getLong(3)),
                Long.valueOf(planId));
    }

    /** 计划的轻量视图。 */
    private static final class CutoverPlanHolder {

        private final CutoverState intent;
        private final CutoverState confirmed;
        private final long routeVersion;

        private CutoverPlanHolder(CutoverState intent, CutoverState confirmed, long routeVersion) {
            this.intent = intent;
            this.confirmed = confirmed;
            this.routeVersion = routeVersion;
        }
    }
}
