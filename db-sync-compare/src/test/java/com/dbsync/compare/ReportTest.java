package com.dbsync.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.enums.CompareVerdict;
import com.dbsync.common.enums.NormalizeRuleType;
import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.model.BlockResult;
import com.dbsync.common.model.CompareReport;
import com.dbsync.common.model.CompareResult;
import com.dbsync.common.model.CompareVerdictSnapshot;
import com.dbsync.common.model.PkRange;
import com.dbsync.common.options.NormalizeOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T36 验收：报告必须含"忽略/归一化规则（本次比对实际生效）"区块（10 条规则全列），
 * 且 CSV 必须流式写（10 万块不 OOM）。
 */
class ReportTest {

    @TempDir
    Path tempDir;

    /**
     * 造块结果。
     *
     * @param blockNo 块号
     * @param matched 是否一致
     * @param diff    差异行
     * @return 块结果
     */
    private static BlockResult block(int blockNo, boolean matched, int diff) {
        PkRange range = new PkRange(Integer.valueOf(blockNo * 100),
                Integer.valueOf((blockNo + 1) * 100), RangeKind.NUMERIC);
        return new BlockResult(blockNo + 1L, blockNo, 9L, range, 100L, matched ? 100L : 100L,
                "sum", "sum", "row", matched ? "row" : "row2", matched, diff);
    }

    /**
     * 造结果。
     *
     * @param blocks 块
     * @return 结果
     */
    private static CompareResult result(List<BlockResult> blocks) {
        int diffBlocks = 0;
        long diffRows = 0L;
        long totalRows = 0L;
        for (BlockResult block : blocks) {
            totalRows += block.getSrcCount();
            if (!block.isMatched()) {
                diffBlocks++;
                diffRows += block.getDiffRows();
            }
        }
        CompareVerdict verdict = CompareResult.verdictOf(totalRows, diffRows, 0.9999d);
        return new CompareResult(7L, 8L, blocks.size(), diffBlocks, totalRows, diffRows, verdict,
                blocks);
    }

    @Test
    @DisplayName("should_containAllRules_When_htmlBuilt：HTML 含规则区块与 10 条规则")
    void should_containAllRules_When_htmlBuilt() {
        CompareReportBuilder builder = new CompareReportBuilder(tempDir.toString());
        ReportFile file = builder.build(7L, result(List.of(block(0, true, 0),
                block(1, false, 3))), NormalizeOptions.defaults());

        assertTrue(file.getHtml().contains(CompareReportBuilder.NORMALIZE_SECTION_TITLE),
                "必须含规则区块标题（CMP-05）");
        int listed = 0;
        for (NormalizeRuleType rule : NormalizeRuleType.values()) {
            assertTrue(file.getHtml().contains(rule.priority()), "缺规则优先级：" + rule.priority());
            assertTrue(file.getHtml().contains(rule.label()), "缺规则名：" + rule.label());
            listed++;
        }
        assertTrue(listed >= 10, "至少 10 条规则全部列出，实际 " + listed);
        assertTrue(Files.exists(Paths.get(file.getHtmlPath())));
        assertTrue(Files.exists(Paths.get(file.getCsvPath())));
    }

    @Test
    @DisplayName("should_writeCsvHeaderAndRows_When_build：CSV 含表头与逐块行")
    void should_writeCsvHeaderAndRows_When_build() throws IOException {
        CompareReportBuilder builder = new CompareReportBuilder(tempDir.toString());
        ReportFile file = builder.build(7L, result(List.of(block(0, true, 0),
                block(1, false, 2))), NormalizeOptions.defaults());

        List<String> lines = Files.readAllLines(Paths.get(file.getCsvPath()), StandardCharsets.UTF_8);
        assertEquals(3, lines.size(), "1 行表头 + 2 行块");
        assertTrue(lines.get(0).startsWith("block_no,"));
        assertTrue(lines.get(2).contains(",0"), "第 2 块应标记不一致");
    }

    @Test
    @DisplayName("should_streamCsv_When_100kBlocks：10 万块不 OOM 且行数正确")
    void should_streamCsv_When_100kBlocks() throws IOException {
        int total = 100_000;
        List<BlockResult> blocks = new ArrayList<BlockResult>(total);
        for (int i = 0; i < total; i++) {
            blocks.add(block(i, true, 0));
        }
        CompareReportBuilder builder = new CompareReportBuilder(tempDir.toString());
        ReportFile file = builder.build(9L, result(blocks), NormalizeOptions.defaults());

        AtomicInteger lines = new AtomicInteger();
        try (java.io.BufferedReader reader = Files.newBufferedReader(
                Paths.get(file.getCsvPath()), StandardCharsets.UTF_8)) {
            while (reader.readLine() != null) {
                lines.incrementAndGet();
            }
        }
        assertEquals(total + 1, lines.get(), "10 万块 + 1 行表头");
        assertTrue(file.getHtml().contains("其余 "), "HTML 只渲染前 500 块，其余指向 CSV");
    }

    @Test
    @DisplayName("should_convertToCompareReport_When_toReport：可转为 common 模型")
    void should_convertToCompareReport_When_toReport() {
        CompareReportBuilder builder = new CompareReportBuilder(tempDir.toString());
        CompareResult result = result(List.of(block(0, true, 0)));
        ReportFile file = builder.build(7L, result, NormalizeOptions.defaults());
        CompareVerdictSnapshot snapshot = new CompareVerdictSnapshot(7L, 8L, 1.0d, 100L, 0L,
                "PASS", Instant.now(), Map.of("key", "value"));

        CompareReport report = builder.toReport(7L, result, snapshot, file);

        assertEquals(7L, report.getJobId());
        assertNotNull(report.getHtml());
        assertEquals(11, report.getNormalizeReport().size());
        assertEquals(file.getCsvPath(), report.getCsvPath());
    }
}
