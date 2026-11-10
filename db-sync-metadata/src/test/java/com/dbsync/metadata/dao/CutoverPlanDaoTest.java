package com.dbsync.metadata.dao;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.model.CutoverPlan;
import com.dbsync.common.options.GateThresholds;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T15 验收（两段式状态机）：{@code updateIntent} 不动 confirmed，{@code confirm} 才让两者一致并推进路由版本。
 */
class CutoverPlanDaoTest extends MetaDaoTestSupport {

    private static final long PLAN_ID = 555001L;
    private static final long TASK_ID = 8801L;

    /**
     * 新建一个 DRAFT/DRAFT 的 ACTIVE 计划。
     *
     * @param dao 计划 DAO
     */
    private void seedPlan(CutoverPlanDao dao) {
        CutoverPlan plan = new CutoverPlan(PLAN_ID, "PLAN-20260101-001", TASK_ID, "订单库切流",
                CutoverState.DRAFT, CutoverState.DRAFT, 0L, CutoverPlanDao.STATUS_ACTIVE,
                GateThresholds.defaults(), 0, 0);
        assertThat(dao.insert(plan, "[\"TENANT\"]", "alice")).isEqualTo(1);
    }

    @Test
    @DisplayName("should_updateIntentWithoutTouchingConfirmed：只改 intent，计划冻结为 INCONSISTENT")
    void should_updateIntentWithoutTouchingConfirmed() {
        CutoverPlanDao dao = new CutoverPlanDao(jdbc);
        seedPlan(dao);
        assertThat(dao.updateIntent(PLAN_ID, CutoverState.PRE_CHECK, 0L)).isEqualTo(1);
        CutoverPlan after = dao.findById(PLAN_ID).orElseThrow();
        assertThat(after.getIntentState()).isEqualTo(CutoverState.PRE_CHECK);
        assertThat(after.getConfirmedState()).isEqualTo(CutoverState.DRAFT);
        assertThat(after.getPlanStatus()).isEqualTo(CutoverPlanDao.STATUS_INCONSISTENT);
        assertThat(after.getRouteVersion()).isZero();
    }

    @Test
    @DisplayName("should_confirmAndAdvanceRouteVersion：confirm 后两者一致且路由版本递增")
    void should_confirmAndAdvanceRouteVersion() {
        CutoverPlanDao dao = new CutoverPlanDao(jdbc);
        seedPlan(dao);
        dao.updateIntent(PLAN_ID, CutoverState.PRE_CHECK, 0L);
        assertThat(dao.confirm(PLAN_ID, CutoverState.PRE_CHECK, 7L, 1L)).isEqualTo(1);
        CutoverPlan after = dao.findById(PLAN_ID).orElseThrow();
        assertThat(after.getIntentState()).isEqualTo(CutoverState.PRE_CHECK);
        assertThat(after.getConfirmedState()).isEqualTo(CutoverState.PRE_CHECK);
        assertThat(after.getRouteVersion()).isEqualTo(7L);
        assertThat(after.getPlanStatus()).isEqualTo(CutoverPlanDao.STATUS_ACTIVE);
        assertThat(after.isInconsistent()).isFalse();
    }

    @Test
    @DisplayName("should_notConfirm_When_confirmedDiffersFromIntent：I5 —— confirmed 必须等于 intent")
    void should_notConfirm_When_confirmedDiffersFromIntent() {
        CutoverPlanDao dao = new CutoverPlanDao(jdbc);
        seedPlan(dao);
        dao.updateIntent(PLAN_ID, CutoverState.PRE_CHECK, 0L);
        // 试图跳过 GRAY_READ 直接确认 GRAY_WRITE → 0 行
        assertThat(dao.confirm(PLAN_ID, CutoverState.GRAY_WRITE, 1L, 1L)).isZero();
        assertThat(dao.findById(PLAN_ID).orElseThrow().getConfirmedState())
                .isEqualTo(CutoverState.DRAFT);
    }

    @Test
    @DisplayName("should_rejectStaleVersion：版本冲突返回 0（跨实例并发安全）")
    void should_rejectStaleVersion() {
        CutoverPlanDao dao = new CutoverPlanDao(jdbc);
        seedPlan(dao);
        assertThat(dao.updateIntent(PLAN_ID, CutoverState.PRE_CHECK, 0L)).isEqualTo(1);
        assertThat(dao.updateIntent(PLAN_ID, CutoverState.GRAY_READ, 0L)).isZero();
    }

    @Test
    @DisplayName("should_findInconsistent：扫描出 intent≠confirmed 的 ACTIVE 计划")
    void should_findInconsistent() {
        CutoverPlanDao dao = new CutoverPlanDao(jdbc);
        seedPlan(dao);
        assertThat(dao.findInconsistent()).isEmpty();
        dao.updateIntent(PLAN_ID, CutoverState.PRE_CHECK, 0L);
        // updateIntent 会把 plan_status 置 INCONSISTENT，因此 ACTIVE 扫描不到它
        assertThat(dao.findInconsistent()).isEmpty();
        // 模拟"旧版本代码留下的 ACTIVE + 不一致"行
        jdbc.update("UPDATE cutover_plan SET plan_status=? WHERE id=?",
                CutoverPlanDao.STATUS_ACTIVE, PLAN_ID);
        List<CutoverPlan> found = dao.findInconsistent();
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getIntentState()).isEqualTo(CutoverState.PRE_CHECK);
    }

    @Test
    @DisplayName("should_updateGrayAndRollback：灰度收敛到 0..100，回滚原因落库")
    void should_updateGrayAndRollback() {
        CutoverPlanDao dao = new CutoverPlanDao(jdbc);
        seedPlan(dao);
        assertThat(dao.updateGray(PLAN_ID, 120, -5)).isEqualTo(1);
        CutoverPlan after = dao.findById(PLAN_ID).orElseThrow();
        assertThat(after.getGrayRatio()).isEqualTo(100);
        assertThat(after.getWriteGrayRatio()).isZero();
        assertThat(dao.markRolledBack(PLAN_ID, "写灰度错误率 2.1% > 1%")).isEqualTo(1);
        assertThat(dao.findById(PLAN_ID).orElseThrow().getRollbackReason())
                .contains("错误率");
    }
}
