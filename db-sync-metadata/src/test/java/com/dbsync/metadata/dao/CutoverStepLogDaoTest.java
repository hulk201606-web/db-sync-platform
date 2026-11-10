package com.dbsync.metadata.dao;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.enums.GateItemCode;
import com.dbsync.common.model.CutoverStep;
import com.dbsync.common.model.GateResult;
import com.dbsync.common.options.GateThresholds;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 切流步骤留痕 DAO 测试：步骤序号递增 + Gate 快照可往返 + 失败计数。
 */
class CutoverStepLogDaoTest extends MetaDaoTestSupport {

    private static final long PLAN_ID = 909001L;

    /**
     * 造一个带 Gate 明细的步骤。
     *
     * @param seq 步骤序号
     * @param result 结果（SUCCESS / BLOCKED）
     * @return 步骤
     */
    private CutoverStep step(int seq, String result) {
        GateResult gate = GateResult.of(List.of(
                new GateResult.GateItem(GateItemCode.COMPARE_PASS_RATE, 0.99999d, 0.9999d, true,
                        "sync_compare_job", null),
                new GateResult.GateItem(GateItemCode.OPEN_DEAD_LETTERS, 0d, 0d, true,
                        "sync_dead_letter", null)), GateThresholds.defaults(), Instant.now());
        return new CutoverStep(PLAN_ID, seq, CutoverState.DRAFT, CutoverState.PRE_CHECK,
                "ADVANCE", result, "SUCCESS".equals(result) ? null : "Gate 未通过", 12L, "alice",
                0L, 1L, false, null, gate);
    }

    @Test
    @DisplayName("should_appendAndFind：步骤按 seq 升序返回，Gate 明细可还原")
    void should_appendAndFind() {
        CutoverStepLogDao dao = new CutoverStepLogDao(jdbc);
        assertThat(dao.nextStepSeq(PLAN_ID)).isEqualTo(1);
        dao.append(step(1, "SUCCESS"), "10.1.1.1");
        assertThat(dao.nextStepSeq(PLAN_ID)).isEqualTo(2);
        dao.append(step(2, "SUCCESS"), "10.1.1.1");
        List<CutoverStep> steps = dao.findByPlan(PLAN_ID);
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getStepSeq()).isEqualTo(1);
        assertThat(steps.get(1).getRouteVersionAfter()).isEqualTo(1L);
        GateResult parsed = steps.get(0).getGateResult();
        assertThat(parsed).isNotNull();
        assertThat(parsed.isPass()).isTrue();
        assertThat(parsed.getItems()).hasSize(2);
        assertThat(parsed.getItems().get(0).getCode()).isEqualTo(GateItemCode.COMPARE_PASS_RATE);
    }

    @Test
    @DisplayName("should_trackFailures：被 Gate 拦截的步骤计入失败数")
    void should_trackFailures() {
        CutoverStepLogDao dao = new CutoverStepLogDao(jdbc);
        dao.append(step(1, "SUCCESS"), null);
        dao.append(step(2, "BLOCKED"), null);
        assertThat(dao.countFailed(PLAN_ID)).isEqualTo(1L);
        CutoverStep last = dao.lastStep(PLAN_ID);
        assertThat(last).isNotNull();
        assertThat(last.getStepSeq()).isEqualTo(2);
        assertThat(last.getResult()).isEqualTo("BLOCKED");
        assertThat(last.getErrorMsg()).contains("Gate");
    }

    @Test
    @DisplayName("should_exposeRawGateJson：完整指标明细可从库里取（模型只留摘要）")
    void should_exposeRawGateJson() {
        CutoverStepLogDao dao = new CutoverStepLogDao(jdbc);
        dao.append(step(1, "SUCCESS"), null);
        String json = dao.gateJsonOf(PLAN_ID, 1);
        assertThat(json).contains("COMPARE_PASS_RATE").contains("OPEN_DEAD_LETTERS");
    }
}
