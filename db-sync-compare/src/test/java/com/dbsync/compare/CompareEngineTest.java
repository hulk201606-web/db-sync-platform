package com.dbsync.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.enums.CompareVerdict;
import com.dbsync.common.model.BlockResult;
import com.dbsync.common.model.CompareResult;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.options.CompareOptions;
import com.dbsync.common.options.NormalizeOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T35 验收：分块校验和 → 差异块下钻 → 汇总。
 *
 * <p>用真实 H2 源库/目标库，1 万行 + 100 行/块（=100 块），注入 5 处差异验证：
 * 只有含差异的那一块被告警，其余 99 块必须"零下钻"。
 */
class CompareEngineTest extends CompareTestSupport {

    /**
     * 装配引擎。
     *
     * @return 引擎
     */
    private CompareEngine engine() {
        return new CompareEngine(taskDao, mappingDao, jobDao, blockDao, diffDao, registry,
                new BlockPlanner(), idGenerator);
    }

    /**
     * 默认选项：100 行/块，并发 4，不下钻关闭。
     *
     * @return 选项
     */
    private static CompareOptions options() {
        return new CompareOptions(100, 4, 0.9999d, NormalizeOptions.defaults(), true);
    }

    @Test
    @DisplayName("should_detectAllDiffs_When_inject5Diffs：diffBlocks=1、diffRows=5")
    void should_detectAllDiffs_When_inject5Diffs() {
        createBothTables("t_ce1");
        insertBoth("t_ce1", 1, 10_000);
        // 5 处差异全部落在第 1 块（id ∈ (0,100]）
        for (long id : new long[] {10L, 20L, 30L, 40L, 50L}) {
            updateName(targetDs, "t_ce1", id, "diff" + id);
        }
        SyncTask task = newTask();
        TableMapping mapping = newMapping(task.getId(), "t_ce1", 10_000);

        CompareResult result = engine().run(task.getId(), null, options());

        assertEquals(100, result.getTotalBlocks(), "1 万行 / 100 行每块 = 100 块");
        assertEquals(1, result.getDiffBlocks(), "只有第 1 块不一致");
        assertEquals(5L, result.getDiffRows(), "注入 5 处差异必须全部抓到");
        assertEquals(10_000L, result.getTotalRows());
        // 通过率 = 0.9995 < 0.9999 但 ≥ 0.9989（阈值 - 0.001）→ WARN
        assertEquals(CompareVerdict.WARN, result.getVerdict());
    }

    @Test
    @DisplayName("should_computeVerdictByThreshold_When_diffRateGiven：阈值判定")
    void should_computeVerdictByThreshold_When_diffRateGiven() {
        // 直接用纯函数验证阈值边界，避免上一用例混淆"抓差异"与"判定"两件事
        assertEquals(CompareVerdict.PASS, CompareResult.verdictOf(10_000L, 0L, 0.9999d));
        assertEquals(CompareVerdict.WARN, CompareResult.verdictOf(10_000L, 5L, 0.9999d));
        assertEquals(CompareVerdict.FAIL, CompareResult.verdictOf(10_000L, 500L, 0.9999d));
    }

    @Test
    @DisplayName("should_zeroDiff_When_identical：passRate=1.0")
    void should_zeroDiff_When_identical() {
        createBothTables("t_ce2");
        insertBoth("t_ce2", 1, 5_000);
        SyncTask task = newTask();
        newMapping(task.getId(), "t_ce2", 5_000);

        CompareResult result = engine().run(task.getId(), null, options());

        assertEquals(0, result.getDiffBlocks());
        assertEquals(0L, result.getDiffRows());
        assertEquals(1.0d, result.passRate(), 1e-9);
        assertEquals(CompareVerdict.PASS, result.getVerdict());
        assertEquals(0L, blockDao.countMismatched(result.getJobId()));
    }

    @Test
    @DisplayName("should_onlyDrillDownDiffBlocks_When_mostMatched：只对差异块下钻")
    void should_onlyDrillDownDiffBlocks_When_mostMatched() {
        createBothTables("t_ce3");
        insertBoth("t_ce3", 1, 10_000);
        updateName(targetDs, "t_ce3", 5_050L, "only-this");
        SyncTask task = newTask();
        TableMapping mapping = newMapping(task.getId(), "t_ce3", 10_000);

        CompareResult result = engine().run(task.getId(), null, options());

        int drilled = 0;
        for (BlockResult block : result.getBlocks()) {
            if (block.getDiffRows() > 0) {
                drilled++;
            }
            if (block.isMatched()) {
                assertEquals(0, block.getDiffRows(), "一致的块不应有差异行");
            }
        }
        assertEquals(1, drilled, "100 块里只有 1 块下钻");
        assertEquals(1L, diffDao.countByJob(result.getJobId()), "只写 1 条差异明细");
        assertEquals(1L, blockDao.countMismatched(result.getJobId()));
        assertEquals(99L, blockDao.countMatched(result.getJobId()));
        assertNotNull(mapping);
    }

    @Test
    @DisplayName("should_finishJob_When_runCompletes：作业落库为 FINISHED 且带规则快照")
    void should_finishJob_When_runCompletes() {
        createBothTables("t_ce4");
        insertBoth("t_ce4", 1, 200);
        SyncTask task = newTask();
        newMapping(task.getId(), "t_ce4", 200);

        CompareResult result = engine().run(task.getId(), null, options());

        CompareJobDaoRowLite row = loadJob(result.getJobId());
        assertEquals("FINISHED", row.state);
        assertEquals(200L, row.totalRows);
        assertTrue(row.normalizeJson.contains("COLUMN_ORDER_BY_NAME"),
                "必须落归一化快照（CMP-05）");
    }

    @Test
    @DisplayName("should_markCanceled_When_cancelTaskBeforeRun：取消后不算任何块")
    void should_markCanceled_When_cancelTaskBeforeRun() {
        createBothTables("t_ce5");
        insertBoth("t_ce5", 1, 1_000);
        SyncTask task = newTask();
        newMapping(task.getId(), "t_ce5", 1_000);
        CompareEngine engine = engine();
        engine.cancelTask(task.getId());

        CompareResult result = engine.run(task.getId(), null, options());

        assertEquals(0, result.getTotalBlocks(), "取消后不应提交任何块");
        assertEquals("CANCELED", loadJob(result.getJobId()).state);
        assertEquals(0L, blockDao.countMismatched(result.getJobId()));
    }

    /**
     * 读作业行（只取测试关心的三列，避免依赖 DAO 的完整行类型）。
     *
     * @param jobId 作业 ID
     * @return 轻量行
     */
    private CompareJobDaoRowLite loadJob(long jobId) {
        return jdbc.queryForObject("SELECT state,total_rows,normalize_json FROM sync_compare_job "
                + "WHERE id=?", (rs, rowNum) -> new CompareJobDaoRowLite(rs.getString(1),
                rs.getLong(2), rs.getString(3)), Long.valueOf(jobId));
    }

    /** 作业行的轻量视图。 */
    private static final class CompareJobDaoRowLite {

        private final String state;
        private final long totalRows;
        private final String normalizeJson;

        private CompareJobDaoRowLite(String state, long totalRows, String normalizeJson) {
            this.state = state;
            this.totalRows = totalRows;
            this.normalizeJson = normalizeJson;
        }
    }
}
