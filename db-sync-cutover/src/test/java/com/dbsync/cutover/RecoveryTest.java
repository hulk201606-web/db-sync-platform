package com.dbsync.cutover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.enums.RouteTarget;
import com.dbsync.common.model.CutoverPlan;
import com.dbsync.common.model.RouteSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T38 验收：启动对齐三路分支。
 *
 * <ol>
 *   <li>外部路由已在 intent → 只补 confirmed；</li>
 *   <li>外部停在 confirmed → 重新下发并等 ack；</li>
 *   <li>外部漂移 → {@code plan_status=INCONSISTENT} 且<b>不推进</b>。</li>
 * </ol>
 */
class RecoveryTest extends CutoverTestSupport {

    private CutoverRecoveryService service(Duration ackTimeout) {
        return new CutoverRecoveryService(planDao, routeRegistry, ackTimeout);
    }

    @Test
    @DisplayName("should_fillConfirmedOnly_When_externalAlreadyAtIntent：分支①只补 confirmed")
    void should_fillConfirmedOnly_When_externalAlreadyAtIntent() {
        long planId = newPlan(CutoverState.GRAY_WRITE, CutoverState.GRAY_READ);
        long taskId = plan(planId).getTaskId();
        // 外部已经是 GRAY_WRITE（上次下发成功但没记账）
        long published = routeRegistry.publish(planId, new RouteSnapshot(planId, 0L,
                RouteTarget.TARGET, 100, true, 5, List.of(), Instant.now()));
        routeRegistry.ack(planId, published);
        CutoverRecoveryService service = service(Duration.ofMillis(200L));

        List<ReconcileResult> results = service.reconcileAll();

        assertEquals(1, results.size());
        ReconcileResult result = results.get(0);
        assertEquals(ReconcileResult.Branch.EXTERNAL_AT_INTENT, result.getBranch());
        assertTrue(result.isChanged());
        CutoverPlan after = plan(planId);
        assertEquals(CutoverState.GRAY_WRITE, after.getIntentState());
        assertEquals(CutoverState.GRAY_WRITE, after.getConfirmedState(), "confirmed 必须被补齐");
        assertEquals(published, after.getRouteVersion(), "route_version 必须记成实际外部版本");
        assertTrue(taskId > 0L);
    }

    @Test
    @DisplayName("should_republishAndAck_When_externalStillAtConfirmed：分支②重新下发")
    void should_republishAndAck_When_externalStillAtConfirmed() {
        long planId = newPlan(CutoverState.GRAY_WRITE, CutoverState.GRAY_READ);
        // 外部还停在 GRAY_READ（下发没生效）
        long v = routeRegistry.publish(planId, new RouteSnapshot(planId, 0L, RouteTarget.TARGET, 5,
                false, 0, List.of(), Instant.now()));
        routeRegistry.ack(planId, v);
        try (FakeSdk sdk = new FakeSdk(routeRegistry, planId, 10L)) {
            CutoverRecoveryService service = service(Duration.ofSeconds(2L));
            List<ReconcileResult> results = service.reconcileAll();

            assertEquals(1, results.size());
            assertEquals(ReconcileResult.Branch.EXTERNAL_AT_CONFIRMED, results.get(0).getBranch());
            assertTrue(results.get(0).isChanged());
        }
        CutoverPlan after = plan(planId);
        assertEquals(CutoverState.GRAY_WRITE, after.getConfirmedState());
        assertEquals(RouteRegistry.Bucket.GRAY_WRITE,
                RouteRegistry.bucketOf(routeRegistry.current(planId)),
                "重新下发必须真的把外部切到 intent 形态");
    }

    @Test
    @DisplayName("should_markInconsistent_When_externalDrift：分支③不推进且标 INCONSISTENT")
    void should_markInconsistent_When_externalDrift() {
        long planId = newPlan(CutoverState.GRAY_READ, CutoverState.DRAFT);
        // 外部漂移到 FULL_TARGET（有人手工改过网关）
        routeRegistry.publish(planId, new RouteSnapshot(planId, 0L, RouteTarget.TARGET, 100, true,
                100, List.of(), Instant.now()));
        CutoverRecoveryService service = service(Duration.ofMillis(200L));

        List<ReconcileResult> results = service.reconcileAll();

        assertEquals(1, results.size());
        assertEquals(ReconcileResult.Branch.DRIFT, results.get(0).getBranch());
        CutoverPlan after = plan(planId);
        assertEquals(CutoverState.GRAY_READ, after.getIntentState(), "不得推进 intent");
        assertEquals(CutoverState.DRAFT, after.getConfirmedState(), "不得改动 confirmed");
        assertEquals("INCONSISTENT", after.getPlanStatus());
        assertFalse(results.get(0).getMessage().isEmpty());
    }

    @Test
    @DisplayName("should_coverInconsistentStatus_When_findNeedingReconcile：崩溃后 status=INCONSISTENT 也要被扫到")
    void should_coverInconsistentStatus_When_findNeedingReconcile() {
        long planId = newPlan(CutoverState.DRAFT, CutoverState.DRAFT);
        // 模拟"写完 intent 就崩了"：updateIntent 会把 status 改成 INCONSISTENT
        exec("UPDATE cutover_plan SET intent_state='PRE_CHECK',plan_status='INCONSISTENT' WHERE id="
                + planId);
        routeRegistry.publish(planId, RouteSnapshot.fullSource(planId, 1L, Instant.now()));

        List<ReconcileResult> results = service(Duration.ofMillis(100L)).reconcileAll();

        assertEquals(1, results.size(), "必须扫到 status=INCONSISTENT 的计划");
        assertEquals(ReconcileResult.Branch.EXTERNAL_AT_INTENT, results.get(0).getBranch());
        assertEquals("ACTIVE", plan(planId).getPlanStatus(), "对齐后应恢复 ACTIVE");
    }

    @Test
    @DisplayName("should_returnEmpty_When_noInconsistentPlan：无不一致时返回空")
    void should_returnEmpty_When_noInconsistentPlan() {
        newPlan(CutoverState.GRAY_READ, CutoverState.GRAY_READ);
        assertTrue(service(Duration.ofMillis(100L)).reconcileAll().isEmpty());
    }
}
