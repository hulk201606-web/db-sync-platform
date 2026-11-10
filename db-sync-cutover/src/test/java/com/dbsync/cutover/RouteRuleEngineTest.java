package com.dbsync.cutover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.enums.RouteTarget;
import com.dbsync.common.model.RouteRule;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T40 验收：路由规则 4 维度（每维度 2 例）。
 *
 * <p>核心不变量：<b>同一主键必须始终落到同一侧</b>（否则同一次请求前后两次读会读到不同的库）。
 */
class RouteRuleEngineTest {

    private final RouteRuleEngine engine = new RouteRuleEngine();

    @Test
    @DisplayName("ratio：命中比例内 → 目标；比例外 → 源")
    void should_splitByRatio_When_ratioRule() {
        List<RouteRule> rules = List.of(RouteRule.ratio(50));
        int target = 0;
        int source = 0;
        for (int i = 0; i < 1_000; i++) {
            if (engine.resolve(rules, "t", Integer.valueOf(i)) == RouteTarget.TARGET) {
                target++;
            } else {
                source++;
            }
        }
        assertTrue(target > 300 && target < 700, "50% 灰度应大致均分，实际 target=" + target);
        assertEquals(1_000, target + source);
    }

    @Test
    @DisplayName("ratio：0% 全源，100% 全目标")
    void should_extremesBehave_When_ratioRule() {
        for (int i = 0; i < 50; i++) {
            assertEquals(RouteTarget.SOURCE,
                    engine.resolve(List.of(RouteRule.ratio(0)), "t", Integer.valueOf(i)));
            assertEquals(RouteTarget.TARGET,
                    engine.resolve(List.of(RouteRule.ratio(100)), "t", Integer.valueOf(i)));
        }
    }

    @Test
    @DisplayName("tableWhitelist：命中白名单走目标，未命中走源")
    void should_matchWhitelist_When_tableRule() {
        List<RouteRule> rules = List.of(RouteRule.tables(List.of("t_order")));
        assertEquals(RouteTarget.TARGET, engine.resolve(rules, "t_order", Integer.valueOf(1)));
        assertEquals(RouteTarget.SOURCE, engine.resolve(rules, "t_user", Integer.valueOf(1)));
        assertEquals(RouteTarget.TARGET, engine.resolve(rules, "db.t_order", Integer.valueOf(1)),
                "带 schema 的表名应能命中简名");
        assertEquals(RouteTarget.SOURCE, engine.resolve(rules, null, Integer.valueOf(1)));
    }

    @Test
    @DisplayName("tableWhitelist：大小写不敏感")
    void should_ignoreCase_When_tableRule() {
        List<RouteRule> rules = List.of(RouteRule.tables(List.of("T_ORDER")));
        assertEquals(RouteTarget.TARGET, engine.resolve(rules, "t_order", Integer.valueOf(9)));
    }

    @Test
    @DisplayName("hashBucket：命中桶走目标，其余走源")
    void should_splitByHashBucket_When_hashRule() {
        List<RouteRule> rules = List.of(RouteRule.hashBucket(16, 3));
        int target = 0;
        for (int i = 0; i < 2_000; i++) {
            if (engine.resolve(rules, "t", Integer.valueOf(i)) == RouteTarget.TARGET) {
                target++;
            }
        }
        assertTrue(target > 50 && target < 300, "1/16 桶应约 125 条，实际 " + target);
    }

    @Test
    @DisplayName("hashBucket：同一主键结果稳定（不可随机）")
    void should_beStable_When_sameKey() {
        List<RouteRule> rules = List.of(RouteRule.hashBucket(8, 2));
        RouteTarget first = engine.resolve(rules, "t", "user-42");
        for (int i = 0; i < 20; i++) {
            assertEquals(first, engine.resolve(rules, "t", "user-42"));
        }
    }

    @Test
    @DisplayName("组合：表命中 且 比例命中 → 目标；表命中但比例未命中 → 源")
    void should_andDimensions_When_combinedRule() {
        RouteRule combined = new RouteRule(Integer.valueOf(50), List.of("t_order"), null, null, true);
        int target = 0;
        for (int i = 0; i < 400; i++) {
            if (engine.resolve(List.of(combined), "t_order", Integer.valueOf(i))
                    == RouteTarget.TARGET) {
                target++;
            }
            assertEquals(RouteTarget.SOURCE,
                    engine.resolve(List.of(combined), "t_user", Integer.valueOf(i)),
                    "表白名单未命中时，即使比例命中也不得走目标");
        }
        assertTrue(target > 100 && target < 300, "组合规则应只让一部分 t_order 走目标，实际 "
                + target);
    }

    @Test
    @DisplayName("组合：多条规则之间是或（第一条命中即返回目标）")
    void should_orAcrossRules_When_multipleRules() {
        List<RouteRule> rules = List.of(RouteRule.tables(List.of("t_a")),
                RouteRule.tables(List.of("t_b")));
        assertEquals(RouteTarget.TARGET, engine.resolve(rules, "t_a", Integer.valueOf(1)));
        assertEquals(RouteTarget.TARGET, engine.resolve(rules, "t_b", Integer.valueOf(1)));
        assertEquals(RouteTarget.SOURCE, engine.resolve(rules, "t_c", Integer.valueOf(1)));
    }

    @Test
    @DisplayName("禁用规则不参与匹配")
    void should_skipDisabled_When_ruleDisabled() {
        RouteRule disabled = new RouteRule(Integer.valueOf(100), null, null, null, false);
        assertEquals(RouteTarget.SOURCE,
                engine.resolve(List.of(disabled), "t", Integer.valueOf(1)));
    }

    @Test
    @DisplayName("fallbackToSource：兜底规则 ratio=0")
    void should_fallbackToSource_When_noRule() {
        assertEquals(RouteTarget.SOURCE,
                engine.resolve(List.of(engine.fallbackToSource()), "t", Integer.valueOf(1)));
        assertEquals(RouteTarget.SOURCE, engine.resolve(List.of(), "t", Integer.valueOf(1)));
        assertEquals(RouteTarget.SOURCE, engine.resolve(null, "t", Integer.valueOf(1)));
        assertEquals(Integer.valueOf(0), engine.fallbackToSource().getRatio());
    }

    @Test
    @DisplayName("snapshotFor：各状态生成正确的读/写路由")
    void should_buildSnapshot_When_stateGiven() {
        com.dbsync.common.model.CutoverPlan plan = new com.dbsync.common.model.CutoverPlan(1L, "P1",
                1L, "n", com.dbsync.common.enums.CutoverState.DRAFT,
                com.dbsync.common.enums.CutoverState.DRAFT, 0L, "ACTIVE",
                com.dbsync.common.options.GateThresholds.defaults(), 10, 20);
        java.time.Instant now = java.time.Instant.now();

        RouteSnapshotAlias read = new RouteSnapshotAlias(
                engine.snapshotFor(plan, com.dbsync.common.enums.CutoverState.GRAY_READ, 1L, now));
        assertEquals(RouteTarget.TARGET, read.readTarget);
        assertEquals(10, read.readRatio);
        assertTrue(!read.writeTarget);

        RouteSnapshotAlias write = new RouteSnapshotAlias(
                engine.snapshotFor(plan, com.dbsync.common.enums.CutoverState.GRAY_WRITE, 2L, now));
        assertEquals(100, write.readRatio);
        assertTrue(write.writeTarget);
        assertEquals(20, write.writeRatio);

        RouteSnapshotAlias full = new RouteSnapshotAlias(
                engine.snapshotFor(plan, com.dbsync.common.enums.CutoverState.FULL_TARGET, 3L, now));
        assertEquals(100, full.writeRatio);

        RouteSnapshotAlias draft = new RouteSnapshotAlias(
                engine.snapshotFor(plan, com.dbsync.common.enums.CutoverState.DRAFT, 4L, now));
        assertEquals(RouteTarget.SOURCE, draft.readTarget);
        assertTrue(!draft.writeTarget);
    }

    /** 快照断言用的轻量视图。 */
    private static final class RouteSnapshotAlias {

        private final RouteTarget readTarget;
        private final int readRatio;
        private final boolean writeTarget;
        private final int writeRatio;

        private RouteSnapshotAlias(com.dbsync.common.model.RouteSnapshot snapshot) {
            this.readTarget = snapshot.getReadTarget();
            this.readRatio = snapshot.getReadGrayRatio();
            this.writeTarget = snapshot.isWriteTarget();
            this.writeRatio = snapshot.getWriteGrayRatio();
        }
    }
}
