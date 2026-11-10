package com.dbsync.cutover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.enums.RouteTarget;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.model.CompareVerdictSnapshot;
import com.dbsync.common.model.CutoverStep;
import com.dbsync.common.model.RouteSnapshot;
import com.dbsync.core.port.CompareVerdictPort;
import com.dbsync.core.port.IncrementalMetricsPort;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T40 验收：回滚（RTO ≤ 10s / 单快照原子切换 / 可跳步）。
 *
 * <p>为什么 RTO 用 10s 而不是 30s：文档要求"实测 ≤ 10s（用 fake SDK 5s 内 ack）"，
 * 其中不含人工确认时间。这里 fake SDK 20ms ack，断言端到端 < 10s（实际是毫秒级）。
 */
class RollbackRtoTest extends CutoverTestSupport {

    /**
     * 全通过 Gate。
     *
     * @return 评估器
     */
    private GateEvaluator passGate() {
        CompareVerdictPort cmp = taskId -> Optional.of(new CompareVerdictSnapshot(1L, taskId,
                1.0d, 100L, 0L, "PASS", Instant.now(), java.util.Map.of()));
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
     * @return 编排器
     */
    private CutoverOrchestrator orchestrator() {
        return new CutoverOrchestrator(planDao, stepLogDao, new CutoverStateMachine(), passGate(),
                routeRegistry, new RouteRuleEngine(), null, Duration.ofSeconds(2L));
    }

    @Test
    @DisplayName("should_under30s_When_rollbackFromGrayWrite：GRAY_WRITE 回滚端到端 < 10s")
    void should_under30s_When_rollbackFromGrayWrite() {
        long planId = newPlan(CutoverState.GRAY_WRITE, CutoverState.GRAY_WRITE);
        routeRegistry.publish(planId, new RouteSnapshot(planId, 0L, RouteTarget.TARGET, 100, true,
                5, java.util.List.of(), Instant.now()));
        try (FakeSdk sdk = new FakeSdk(routeRegistry, planId, 20L)) {
            long start = System.currentTimeMillis();
            CutoverStep step = orchestrator().rollback(planId, "admin", "目标库出现数据异常，立即回滚");
            long elapsed = System.currentTimeMillis() - start;

            assertEquals("SUCCESS", step.getResult());
            assertTrue(elapsed < 10_000L, "回滚 RTO 必须 ≤ 10s，实际 " + elapsed + "ms");
        }
        assertEquals(CutoverState.ROLLED_BACK, plan(planId).getIntentState());
        assertEquals(CutoverState.ROLLED_BACK, plan(planId).getConfirmedState());
        assertEquals("目标库出现数据异常，立即回滚", plan(planId).getRollbackReason());
    }

    @Test
    @DisplayName("should_atomicSwitch_When_rollback：读回源与停写目标在同一次 publish")
    void should_atomicSwitch_When_rollback() {
        long planId = newPlan(CutoverState.GRAY_WRITE, CutoverState.GRAY_WRITE);
        routeRegistry.publish(planId, new RouteSnapshot(planId, 0L, RouteTarget.TARGET, 100, true,
                5, java.util.List.of(), Instant.now()));
        long versionBefore = routeRegistry.currentVersion(planId);
        try (FakeSdk sdk = new FakeSdk(routeRegistry, planId, 10L)) {
            orchestrator().rollback(planId, "admin", "回滚演练");
        }

        RouteSnapshot current = routeRegistry.current(planId);
        assertNotNull(current);
        assertEquals(versionBefore + 1L, current.getVersion(), "回滚只允许发布一次快照");
        assertEquals(RouteTarget.SOURCE, current.getReadTarget());
        assertEquals(100, current.getReadGrayRatio());
        assertFalse(current.isWriteTarget(), "同一份快照里必须同时停写目标");
        assertEquals(0, current.getWriteGrayRatio());
        assertEquals(RouteRegistry.Bucket.SOURCE_ONLY, RouteRegistry.bucketOf(current));
    }

    @Test
    @DisplayName("should_jump_When_rollbackFromGrayWrite：GRAY_WRITE→ROLLED_BACK 一次成功（可跳步）")
    void should_jump_When_rollbackFromGrayWrite() {
        long planId = newPlan(CutoverState.GRAY_WRITE, CutoverState.GRAY_WRITE);
        try (FakeSdk sdk = new FakeSdk(routeRegistry, planId, 10L)) {
            CutoverStep step = orchestrator().rollback(planId, "admin", "跳步回滚验证");
            assertEquals(CutoverState.GRAY_WRITE, step.getFromState());
            assertEquals(CutoverState.ROLLED_BACK, step.getToState());
            assertEquals("ROLLBACK", step.getAction());
        }
        assertEquals("FROZEN", plan(planId).getPlanStatus(), "回滚后计划应冻结，避免再次推进");
        assertTrue(!plan(planId).isInconsistent());
    }

    @Test
    @DisplayName("should_requireReason_When_rollback：无原因拒绝回滚")
    void should_requireReason_When_rollback() {
        long planId = newPlan(CutoverState.GRAY_READ, CutoverState.GRAY_READ);
        assertThrows(DbSyncException.class, () -> orchestrator().rollback(planId, "admin", "  "));
    }

    @Test
    @DisplayName("should_refuse_When_rollbackFromDraft：DRAFT 不允许回滚")
    void should_refuse_When_rollbackFromDraft() {
        long planId = newPlan(CutoverState.DRAFT, CutoverState.DRAFT);
        assertThrows(DbSyncException.class,
                () -> orchestrator().rollback(planId, "admin", "未开始就回滚不合理"));
    }

    @Test
    @DisplayName("should_writeStepLog_When_rollback：回滚必须留痕且带版本前后值")
    void should_writeStepLog_When_rollback() {
        long planId = newPlan(CutoverState.GRAY_READ, CutoverState.GRAY_READ);
        try (FakeSdk sdk = new FakeSdk(routeRegistry, planId, 10L)) {
            orchestrator().rollback(planId, "admin", "留痕验证");
        }
        assertEquals(1, stepLogDao.findByPlan(planId).size());
        assertEquals(0L, stepLogDao.lastStep(planId).getRouteVersionBefore());
        assertEquals(1L, stepLogDao.lastStep(planId).getRouteVersionAfter());
    }
}
