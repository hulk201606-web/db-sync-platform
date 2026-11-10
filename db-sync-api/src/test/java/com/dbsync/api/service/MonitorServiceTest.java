package com.dbsync.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.api.ApiTestSupport;
import com.dbsync.api.dto.monitor.OverviewView;
import com.dbsync.api.metrics.SyncMetrics;
import com.dbsync.common.enums.DbType;
import com.dbsync.common.enums.ShardState;
import com.dbsync.common.enums.TaskState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 大盘聚合测试（T45 验收 3：任务数 / 分片热力 / TPS / 死信数）。
 *
 * <p>真实 DAO + 真实 {@link SyncMetrics}（SimpleMeterRegistry），
 * 不 mock —— 聚合口径错不错，靠这个测试兜住。
 */
class MonitorServiceTest extends ApiTestSupport {

    private MonitorService monitorService;
    private SyncMetrics syncMetrics;

    @BeforeEach
    void setUpService() {
        syncMetrics = new SyncMetrics(new SimpleMeterRegistry());
        monitorService = new MonitorService(taskDao, shardDao, deadLetterDao, cutoverPlanDao,
                instanceDao, syncMetrics, null);
    }

    @Test
    @DisplayName("空库：总数 0，各状态键齐全（前端不用判空）")
    void should_returnZeros_When_empty() {
        OverviewView view = monitorService.overview();
        assertEquals(0, view.totalTasks());
        assertEquals(0L, view.committedRows());
        assertEquals(TaskState.values().length, view.taskCounts().size());
        assertEquals(ShardState.values().length, view.shardCounts().size());
        assertEquals(0, view.taskCounts().get("RUNNING").intValue());
        assertEquals(0L, view.deadLetterOpen());
        assertTrue(view.recentAlerts().isEmpty());
    }

    @Test
    @DisplayName("任务状态计数 + 只对活跃任务出分片热力")
    void should_countTasks_andHeatForActiveOnly() {
        long src = newDataSource("mon-src", DbType.MYSQL);
        long tgt = newDataSource("mon-tgt", DbType.DM);
        long running = newTask(src, tgt);
        long draft = newTask(src, tgt);
        jdbc.update("UPDATE sync_task SET state=? WHERE id=?", TaskState.RUNNING.name(),
                Long.valueOf(running));

        OverviewView view = monitorService.overview();
        assertEquals(2, view.totalTasks());
        assertEquals(1, view.taskCounts().get("RUNNING").intValue());
        assertEquals(1, view.taskCounts().get("DRAFT").intValue());
        assertTrue(view.shardHeat().containsKey(String.valueOf(running)), "活跃任务要出现在热力里");
        assertEquals(false, view.shardHeat().containsKey(String.valueOf(draft)),
                "DRAFT 任务不出热力（省响应体）");
        assertTrue(view.tps().containsKey(String.valueOf(running)));
    }

    @Test
    @DisplayName("TPS 与 committedRows 汇总：totalTps = 各任务之和")
    void should_sumTps() {
        long src = newDataSource("mon-src2", DbType.MYSQL);
        long tgt = newDataSource("mon-tgt2", DbType.DM);
        long task1 = newTask(src, tgt);
        long task2 = newTask(src, tgt);
        syncMetrics.onBatchCommitted(task1, "t_order", 300, 12L);
        syncMetrics.onBatchCommitted(task2, "t_item", 100, 8L);

        OverviewView view = monitorService.overview();
        double expected = syncMetrics.tps(task1) + syncMetrics.tps(task2);
        assertEquals(expected, view.totalTps(), 1e-9d);
        assertTrue(view.tps().get(String.valueOf(task1)).doubleValue() > 0.0d,
                "刚提交的批量必须体现在 TPS 上");
        Map<String, Integer> cutover = view.cutoverPlans();
        assertEquals(0, cutover.get("DRAFT").intValue(), "无计划时各状态恒为 0");
    }
}
