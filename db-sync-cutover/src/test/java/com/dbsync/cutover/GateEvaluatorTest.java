package com.dbsync.cutover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.enums.DeadLetterSourceType;
import com.dbsync.common.enums.DeadLetterStatus;
import com.dbsync.common.enums.GateItemCode;
import com.dbsync.common.model.CompareVerdictSnapshot;
import com.dbsync.common.model.DeadLetter;
import com.dbsync.common.model.GateResult;
import com.dbsync.core.port.CompareVerdictPort;
import com.dbsync.core.port.IncrementalMetricsPort;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T39 验收：Gate 四指标（卡点不可绕过）。
 *
 * <p>16 种组合（每指标 2 状态）必须与"全部满足才通过"严格一致，任何一种"取数失败/过期/
 * 有死信"都必须 BLOCK —— 宁可拦住，不可放过。
 */
class GateEvaluatorTest extends CutoverTestSupport {

    /**
     * 可编程的比对端口。
     *
     * @param snapshot 结论（null 表示"从未完成过"）
     * @return 端口
     */
    private static CompareVerdictPort comparePort(CompareVerdictSnapshot snapshot) {
        return taskId -> Optional.ofNullable(snapshot);
    }

    /**
     * 可编程的增量指标端口。
     *
     * @param lag      延迟
     * @param failRate 失败率
     * @return 端口
     */
    private static IncrementalMetricsPort metricsPort(long lag, double failRate) {
        return new IncrementalMetricsPort() {
            @Override
            public long latencyP95(long taskId) {
                return lag;
            }

            @Override
            public long backlog(long taskId) {
                return 0L;
            }

            @Override
            public double failRate(long taskId, Duration window) {
                return failRate;
            }

            @Override
            public Instant lastApplyAt(long taskId) {
                return Instant.now();
            }
        };
    }

    /**
     * 新鲜通过的结论快照。
     *
     * @param passRate 通过率
     * @param minutesAgo 完成于几分钟前
     * @return 快照
     */
    private static CompareVerdictSnapshot verdict(double passRate, long minutesAgo) {
        String state = passRate >= 0.9999d ? "PASS" : "FAIL";
        return new CompareVerdictSnapshot(1L, 1L, passRate, 10_000L, 0L, state,
                Instant.now().minusSeconds(minutesAgo * 60L), Map.of());
    }

    @Test
    @DisplayName("should_pass_When_allFourOk：四项全满足 → PASS")
    void should_pass_When_allFourOk() {
        long planId = newPlan(CutoverState.PRE_CHECK, CutoverState.PRE_CHECK);
        GateEvaluator evaluator = new GateEvaluator(comparePort(verdict(0.99995d, 10L)),
                metricsPort(1_000L, 0.0d), deadLetterDao, planDao);

        GateResult result = evaluator.evaluate(planId);

        assertTrue(result.isPass(), result.toString());
        assertEquals(4, result.getItems().size());
        for (GateResult.GateItem item : result.getItems()) {
            assertTrue(item.isPass(), item.toString());
        }
    }

    @Test
    @DisplayName("should_block_When_compareResultStale：结果 7h 前 → BLOCK 且原因含 过期")
    void should_block_When_compareResultStale() {
        long planId = newPlan(CutoverState.PRE_CHECK, CutoverState.PRE_CHECK);
        GateEvaluator evaluator = new GateEvaluator(comparePort(verdict(1.0d, 7L * 60L)),
                metricsPort(100L, 0.0d), deadLetterDao, planDao);

        GateResult result = evaluator.evaluate(planId);

        assertFalse(result.isPass());
        assertTrue(result.getReasons().toString().contains("过期"), result.getReasons().toString());
        GateResult.GateItem g1 = item(result, GateItemCode.COMPARE_PASS_RATE);
        assertTrue(g1.getHint().contains("过期"));
    }

    @Test
    @DisplayName("should_block_When_noCompareResult：从未比对过 → BLOCK")
    void should_block_When_noCompareResult() {
        long planId = newPlan(CutoverState.PRE_CHECK, CutoverState.PRE_CHECK);
        GateEvaluator evaluator = new GateEvaluator(comparePort(null), metricsPort(0L, 0.0d),
                deadLetterDao, planDao);

        GateResult result = evaluator.evaluate(planId);

        assertFalse(result.isPass());
        assertTrue(item(result, GateItemCode.COMPARE_PASS_RATE).getHint().contains("无有效比对结论"));
    }

    @Test
    @DisplayName("should_block_When_deadLetterNonZero：1 条 OPEN 死信 → BLOCK")
    void should_block_When_deadLetterNonZero() {
        long planId = newPlan(CutoverState.PRE_CHECK, CutoverState.PRE_CHECK);
        long taskId = plan(planId).getTaskId();
        deadLetterDao.insert(new DeadLetter(idGenerator.nextId(), taskId, 1L,
                DeadLetterSourceType.CHANGE, 9L, "{\"id\":1}", "{}", "E4010", "boom", "digest",
                DeadLetterStatus.OPEN, 0));
        GateEvaluator evaluator = new GateEvaluator(comparePort(verdict(1.0d, 1L)),
                metricsPort(0L, 0.0d), deadLetterDao, planDao);

        GateResult result = evaluator.evaluate(planId);

        assertFalse(result.isPass());
        GateResult.GateItem g4 = item(result, GateItemCode.OPEN_DEAD_LETTERS);
        assertEquals(1.0d, g4.getActual());
        assertFalse(g4.isPass());
    }

    @Test
    @DisplayName("should_passWithHint_When_noIncrementalData：延迟 0 视为无数据，放行并给 hint")
    void should_passWithHint_When_noIncrementalData() {
        long planId = newPlan(CutoverState.PRE_CHECK, CutoverState.PRE_CHECK);
        GateEvaluator evaluator = new GateEvaluator(comparePort(verdict(1.0d, 1L)),
                metricsPort(0L, 0.0d), deadLetterDao, planDao);

        GateResult result = evaluator.evaluate(planId);

        assertTrue(result.isPass());
        GateResult.GateItem g2 = item(result, GateItemCode.INCREMENT_LAG_P95);
        assertTrue(g2.isPass());
        assertEquals("无增量数据", g2.getHint());
    }

    @Test
    @DisplayName("should_block_When_lagExceedsThreshold：延迟超阈值 → BLOCK")
    void should_block_When_lagExceedsThreshold() {
        long planId = newPlan(CutoverState.PRE_CHECK, CutoverState.PRE_CHECK);
        GateEvaluator evaluator = new GateEvaluator(comparePort(verdict(1.0d, 1L)),
                metricsPort(9_999L, 0.0d), deadLetterDao, planDao);

        GateResult result = evaluator.evaluate(planId);

        assertFalse(result.isPass());
        assertFalse(item(result, GateItemCode.INCREMENT_LAG_P95).isPass());
    }

    @Test
    @DisplayName("should_block_When_failRateExceedsThreshold：失败率超阈值 → BLOCK")
    void should_block_When_failRateExceedsThreshold() {
        long planId = newPlan(CutoverState.PRE_CHECK, CutoverState.PRE_CHECK);
        GateEvaluator evaluator = new GateEvaluator(comparePort(verdict(1.0d, 1L)),
                metricsPort(10L, 0.5d), deadLetterDao, planDao);

        GateResult result = evaluator.evaluate(planId);

        assertFalse(result.isPass());
        assertFalse(item(result, GateItemCode.DUAL_WRITE_FAIL_RATE).isPass());
    }

    @Test
    @DisplayName("should_cover16Combinations_When_metricsVary：16 种组合与 AND 语义一致")
    void should_cover16Combinations_When_metricsVary() {
        long planId = newPlan(CutoverState.PRE_CHECK, CutoverState.PRE_CHECK);
        long taskId = plan(planId).getTaskId();
        int combos = 0;
        for (int mask = 0; mask < 16; mask++) {
            boolean compareOk = (mask & 1) == 0;
            boolean lagOk = (mask & 2) == 0;
            boolean failOk = (mask & 4) == 0;
            boolean dlOk = (mask & 8) == 0;

            // 每轮清空死信，保证组合之间不串
            jdbc.update("DELETE FROM sync_dead_letter WHERE task_id=?", Long.valueOf(taskId));
            if (!dlOk) {
                deadLetterDao.insert(new DeadLetter(idGenerator.nextId(), taskId, 1L,
                        DeadLetterSourceType.CHANGE, 9L, "{}", "{}", "E4010", "boom", "d", 
                        DeadLetterStatus.OPEN, 0));
            }
            GateEvaluator evaluator = new GateEvaluator(
                    comparePort(verdict(compareOk ? 1.0d : 0.5d, 1L)),
                    metricsPort(lagOk ? 100L : 9_999L, failOk ? 0.0d : 0.5d),
                    deadLetterDao, planDao);

            GateResult result = evaluator.evaluate(planId);
            boolean expected = compareOk && lagOk && failOk && dlOk;
            assertEquals(expected, result.isPass(), "组合 mask=" + mask + " 判定不符：" + result);
            combos++;
        }
        assertEquals(16, combos);
    }

    @Test
    @DisplayName("should_neverCompareInFrontend_When_gateResultSerialized：结果只含 pass 不含阈值比较逻辑")
    void should_neverCompareInFrontend_When_gateResultSerialized() {
        long planId = newPlan(CutoverState.PRE_CHECK, CutoverState.PRE_CHECK);
        GateEvaluator evaluator = new GateEvaluator(comparePort(verdict(1.0d, 1L)),
                metricsPort(100L, 0.0d), deadLetterDao, planDao);

        GateResult result = evaluator.evaluate(planId);

        assertEquals(4, result.getItems().size());
        for (GateResult.GateItem item : result.getItems()) {
            assertNotNull(item.getCode());
            // 实测值与阈值都随结果下发（前端只展示，不做比较）
            assertTrue(item.getThreshold() != 0.0d || item.getCode() == GateItemCode.OPEN_DEAD_LETTERS);
        }
        assertNotNull(result.getThresholdSnapshot());
        assertEquals(List.of(), result.getReasons(), "全通过时不应有阻断原因");
    }

    /**
     * 取指定指标。
     *
     * @param result 结果
     * @param code   指标编码
     * @return 指标项
     */
    private static GateResult.GateItem item(GateResult result, GateItemCode code) {
        for (GateResult.GateItem item : result.getItems()) {
            if (item.getCode() == code) {
                return item;
            }
        }
        throw new AssertionError("找不到指标：" + code);
    }
}
