package com.dbsync.api.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 指标测试（T44 验收 3/4）：15 个 {@code dbsync.*} 指标全部注册；TPS 必须是 60 秒滑窗。
 */
class SyncMetricsTest {

    @Test
    @DisplayName("15 个 dbsync.* 指标全部注册（/actuator/prometheus 可见）")
    void should_registerAll15Metrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SyncMetrics metrics = new SyncMetrics(registry);
        metrics.onBatchCommitted(1001L, "T_ORDER", 1000L, 12L);
        metrics.onChangesApplied(10L);
        metrics.onLeaseTakeover(1L);

        Set<String> names = registry.getMeters().stream().map(m -> m.getId().getName())
                .collect(Collectors.toCollection(TreeSet::new));
        List<String> expected = List.of("dbsync.migration.rows.total", "dbsync.migration.tps",
                "dbsync.migration.shards", "dbsync.cdc.lag.p95.ms", "dbsync.cdc.backlog",
                "dbsync.cdc.apply.rate", "dbsync.cdc.apply.fail.rate", "dbsync.deadletter.open",
                "dbsync.compare.blocks", "dbsync.compare.pass.rate", "dbsync.cutover.state",
                "dbsync.instance.shards", "dbsync.pool.active", "dbsync.batch.duration",
                "dbsync.lease.takeover.total");
        for (String name : expected) {
            assertTrue(names.contains(name), "缺少指标 " + name + "，实际有 " + names);
        }
        assertEquals(15, expected.size());
    }

    @Test
    @DisplayName("行数计数按 taskId/table 打 tag 累加")
    void should_accumulateRowsByTaskAndTable() {
        SyncMetrics metrics = new SyncMetrics(new SimpleMeterRegistry());
        metrics.onBatchCommitted(1L, "T1", 500L, 5L);
        metrics.onBatchCommitted(1L, "T1", 500L, 5L);
        metrics.onBatchCommitted(1L, "T2", 7L, 5L);
        assertEquals(1000.0d, metrics.totalRows(1L, "T1"), 1e-9);
        assertEquals(7.0d, metrics.totalRows(1L, "T2"), 1e-9);
    }

    @Test
    @DisplayName("TPS 是 12×5s 滑窗而非瞬时（批后 60 秒内持续有效）")
    void should_useSlidingWindow_For_tps() {
        SyncMetrics.TpsWindow window = new SyncMetrics.TpsWindow(1_000L);
        window.add(600L, 1_100L);
        // 60 秒窗口 → 600 / 60 = 10 rows/s
        assertEquals(10.0d, window.tps(1_100L), 1e-6);
        // 30 秒后仍在窗口内（桶未滚出）
        assertEquals(10.0d, window.tps(31_000L), 1e-6);
        // 65 秒后滚出窗口 → 归零（瞬时口径会在批后立刻掉 0，滑窗口径保留 60 秒）
        assertEquals(0.0d, window.tps(66_000L), 1e-6);
    }

    @Test
    @DisplayName("TPS 跨多个桶累加")
    void should_sumAcrossBuckets() {
        SyncMetrics.TpsWindow window = new SyncMetrics.TpsWindow(0L);
        window.add(120L, 1_000L);
        window.add(240L, 7_000L);
        window.add(240L, 12_000L);
        // 合计 600 / 60 = 10
        assertEquals(10.0d, window.tps(12_500L), 1e-6);
    }

    @Test
    @DisplayName("Gauge 可写可读（带 tag）")
    void should_setAndGetGauge() {
        SyncMetrics metrics = new SyncMetrics(new SimpleMeterRegistry());
        metrics.setGauge("cdc.backlog|taskId=9", 42.0d);
        assertEquals(42.0d, metrics.gauge("cdc.backlog|taskId=9"), 1e-9);
        metrics.setGauge("cdc.backlog|taskId=9", 7.0d);
        assertEquals(7.0d, metrics.gauge("cdc.backlog|taskId=9"), 1e-9);
        assertEquals(0.0d, metrics.gauge("cdc.backlog|taskId=404"), 1e-9);
    }

    @Test
    @DisplayName("切流状态映射单调（DRAFT 最小、ROLLED_BACK 最大）")
    void should_mapCutoverStateMonotonically() {
        assertEquals(0.0d, com.dbsync.api.schedule.HeartbeatJob.stateCode(
                com.dbsync.common.enums.CutoverState.DRAFT), 1e-9);
        assertTrue(com.dbsync.api.schedule.HeartbeatJob.stateCode(
                com.dbsync.common.enums.CutoverState.GRAY_WRITE)
                > com.dbsync.api.schedule.HeartbeatJob.stateCode(
                        com.dbsync.common.enums.CutoverState.PRE_CHECK));
        assertTrue(com.dbsync.api.schedule.HeartbeatJob.stateCode(
                com.dbsync.common.enums.CutoverState.ROLLED_BACK)
                > com.dbsync.api.schedule.HeartbeatJob.stateCode(
                        com.dbsync.common.enums.CutoverState.FULL_TARGET));
        assertEquals(0.0d, com.dbsync.api.schedule.HeartbeatJob.stateCode(null), 1e-9);
    }
}
