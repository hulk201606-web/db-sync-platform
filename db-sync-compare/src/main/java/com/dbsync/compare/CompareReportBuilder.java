package com.dbsync.compare;

import com.dbsync.common.enums.NormalizeRuleType;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.BlockResult;
import com.dbsync.common.model.CompareReport;
import com.dbsync.common.model.CompareResult;
import com.dbsync.common.model.CompareVerdictSnapshot;
import com.dbsync.common.options.NormalizeOptions;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 比对报告构建器（T36）：HTML + CSV，<b>必须</b>内嵌"忽略/归一化规则（本次比对实际生效）"区块。
 *
 * <p><b>CMP-05 硬约束（不得静默忽略）</b>：报告里逐条列出 P0~P10 的开关与参数，
 * 且把完整的 {@link NormalizeOptions} 快照（{@code normalize_json}）随作业一起落库。
 * 只列"开了哪些"是不够的：同一开关的不同参数（如 {@code FLOAT_TOLERANCE=1e-9} 与 {@code 1e-6}）
 * 会得出不同结论，事后复盘必须能看到"当时的参数"。
 *
 * <p><b>CSV 用流式写</b>：10 万块的报告不能先拼成一个巨大字符串（会 OOM），
 * 逐行 {@link BufferedWriter#write} 后立即释放。
 */
public class CompareReportBuilder {

    private static final Logger log = LoggerFactory.getLogger(CompareReportBuilder.class);

    /** 报告的固定区块标题（PRD CMP-05 原文，测试按此断言）。 */
    public static final String NORMALIZE_SECTION_TITLE = "忽略/归一化规则（本次比对实际生效）";

    private final Path reportDir;

    /**
     * 构造。
     *
     * @param reportDir 报告目录（null 或空用 {@code ./reports}）
     */
    public CompareReportBuilder(String reportDir) {
        this.reportDir = Paths.get(reportDir == null || reportDir.isBlank() ? "reports" : reportDir);
    }

    /**
     * 生成报告。
     *
     * @param jobId    作业 ID
     * @param result   比对结果
     * @param snapshot 归一化选项快照（决定报告里规则的开关展示）
     * @return 报告产物（含落盘路径）
     * @throws DbSyncException {@code E5010} 落盘失败
     */
    public ReportFile build(long jobId, CompareResult result, NormalizeOptions snapshot) {
        NormalizeOptions options = snapshot == null ? NormalizeOptions.defaults() : snapshot;
        Map<String, String> normalizeReport = options.toReportMap();
        try {
            Files.createDirectories(reportDir);
            String html = composeHtml(jobId, result, options, normalizeReport);
            Path htmlPath = reportDir.resolve("compare-" + jobId + ".html");
            Files.write(htmlPath, html.getBytes(StandardCharsets.UTF_8));
            Path csvPath = reportDir.resolve("compare-" + jobId + ".csv");
            writeCsv(csvPath, result);
            log.info("比对报告已生成 job={} html={} csv={}", jobId, htmlPath, csvPath);
            return new ReportFile(jobId, htmlPath.toAbsolutePath().toString(),
                    csvPath.toAbsolutePath().toString(), html, normalizeReport);
        } catch (IOException e) {
            throw new DbSyncException(ErrorCode.REPORT_BUILD_FAILED, e,
                    "报告落盘失败：" + e.getMessage());
        }
    }

    /**
     * 转成 common 的 {@link CompareReport}（给 API 层直接返回，不必暴露文件系统细节）。
     *
     * @param jobId   作业 ID
     * @param result  比对结果
     * @param verdict 结论快照
     * @param file    已生成的报告文件
     * @return 报告
     */
    public CompareReport toReport(long jobId, CompareResult result, CompareVerdictSnapshot verdict,
                                  ReportFile file) {
        return new CompareReport(jobId, verdict, file.getNormalizeReport(), file.getHtml(),
                file.getCsvPath(), Instant.now());
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 拼 HTML。
     *
     * @param jobId           作业 ID
     * @param result          比对结果
     * @param options         归一化选项
     * @param normalizeReport 规则报告
     * @return HTML
     */
    private String composeHtml(long jobId, CompareResult result, NormalizeOptions options,
                               Map<String, String> normalizeReport) {
        StringBuilder html = new StringBuilder(8192);
        html.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<title>比对报告 #").append(jobId).append("</title>")
                .append("<style>body{font-family:sans-serif;margin:24px;color:#222}")
                .append("table{border-collapse:collapse;width:100%;margin:12px 0}")
                .append("th,td{border:1px solid #ccc;padding:6px 8px;font-size:13px}")
                .append("th{background:#f4f4f4;text-align:left}")
                .append(".bad{color:#c00}.ok{color:#080}.warn{color:#b60}")
                .append("h2{margin-top:28px}</style></head><body>");
        html.append("<h1>数据比对报告</h1>");
        html.append("<p>作业：").append(jobId).append("；任务：").append(result.getTaskId())
                .append("；生成时间：").append(Instant.now()).append("</p>");

        html.append("<h2>结论</h2><table>")
                .append("<tr><th>结论</th><th>通过率</th><th>总块数</th><th>差异块</th>")
                .append("<th>总行数</th><th>差异行数</th></tr>");
        html.append("<tr><td class=\"").append(verdictClass(result))
                .append("\">").append(result.getVerdict()).append("</td><td>")
                .append(String.format("%.6f", result.passRate())).append("</td><td>")
                .append(result.getTotalBlocks()).append("</td><td>")
                .append(result.getDiffBlocks()).append("</td><td>")
                .append(result.getTotalRows()).append("</td><td>")
                .append(result.getDiffRows()).append("</td></tr></table>");

        // PRD CMP-05：规则区块必须存在且逐条列出
        html.append("<h2>").append(NORMALIZE_SECTION_TITLE).append("</h2><ul>");
        for (NormalizeRuleType rule : NormalizeRuleType.values()) {
            String detail = normalizeReport.get(rule.name());
            boolean enabled = detail != null && !detail.startsWith("已关闭");
            html.append("<li>").append(enabled ? "✔" : "✘").append(' ')
                    .append(rule.priority()).append(' ').append(rule.label())
                    .append(rule.isMandatory() ? "（强制）" : "（可配）")
                    .append(" —— ").append(detail == null ? "未配置" : detail)
                    .append("</li>");
        }
        html.append("</ul>");
        html.append("<p>完整快照（JSON）：<code>").append(escape(String.valueOf(options)))
                .append("</code></p>");

        html.append("<h2>分块明细（前 500 块）</h2><table>")
                .append("<tr><th>块号</th><th>映射</th><th>区间</th><th>源行数</th>")
                .append("<th>目标行数</th><th>一致</th><th>差异行</th></tr>");
        List<BlockResult> blocks = result.getBlocks();
        int limit = Math.min(blocks.size(), 500);
        for (int i = 0; i < limit; i++) {
            BlockResult block = blocks.get(i);
            html.append("<tr><td>").append(block.getBlockNo()).append("</td><td>")
                    .append(block.getTableMappingId()).append("</td><td>")
                    .append(block.getRange()).append("</td><td>").append(block.getSrcCount())
                    .append("</td><td>").append(block.getTgtCount()).append("</td><td class=\"")
                    .append(block.isMatched() ? "ok" : "bad").append("\">")
                    .append(block.isMatched() ? "一致" : "不一致").append("</td><td>")
                    .append(block.getDiffRows()).append("</td></tr>");
        }
        html.append("</table>");
        if (blocks.size() > limit) {
            html.append("<p>其余 ").append(blocks.size() - limit)
                    .append(" 块见 CSV（流式导出，避免页面过大）。</p>");
        }
        html.append("</body></html>");
        return html.toString();
    }

    /**
     * 流式写 CSV（10 万块也不会 OOM）。
     *
     * @param csvPath 目标路径
     * @param result  结果
     * @throws IOException 写失败
     */
    private void writeCsv(Path csvPath, CompareResult result) throws IOException {
        try (Writer fileWriter = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8);
             BufferedWriter writer = new BufferedWriter(fileWriter, 64 * 1024)) {
            writer.write("block_no,table_mapping_id,pk_start,pk_end,src_count,tgt_count,"
                    + "matched,diff_rows\n");
            for (BlockResult block : result.getBlocks()) {
                writer.write(Integer.toString(block.getBlockNo()));
                writer.write(',');
                writer.write(Long.toString(block.getTableMappingId()));
                writer.write(',');
                writer.write(csvCell(block.getRange() == null ? null
                        : block.getRange().getStart()));
                writer.write(',');
                writer.write(csvCell(block.getRange() == null ? null : block.getRange().getEnd()));
                writer.write(',');
                writer.write(Long.toString(block.getSrcCount()));
                writer.write(',');
                writer.write(Long.toString(block.getTgtCount()));
                writer.write(',');
                writer.write(block.isMatched() ? "1" : "0");
                writer.write(',');
                writer.write(Integer.toString(block.getDiffRows()));
                writer.write('\n');
            }
        }
    }

    /**
     * CSV 单元格转义。
     *
     * @param value 值
     * @return 转义后的文本
     */
    private static String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (text.indexOf(',') < 0 && text.indexOf('"') < 0 && text.indexOf('\n') < 0) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    /**
     * HTML 转义。
     *
     * @param text 原文
     * @return 转义后
     */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 结论对应的样式类。
     *
     * @param result 结果
     * @return CSS 类名
     */
    private static String verdictClass(CompareResult result) {
        switch (result.getVerdict()) {
            case PASS:
                return "ok";
            case WARN:
                return "warn";
            default:
                return "bad";
        }
    }
}
