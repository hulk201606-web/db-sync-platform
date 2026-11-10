package com.dbsync.compare;

import java.util.Map;

/**
 * 报告产物（// ADDED：04 文档 T36 的 {@code CompareReportBuilder.build} 返回 {@code ReportFile}，
 * 但 common 模型清单只有 {@code CompareReport}（内联 HTML 字符串 + CSV 路径）。
 * {@code ReportFile} 额外承载"落盘路径 + 规则快照"，避免把大文件内容塞进 DB；两者可互转）。
 *
 * <p>不可变。
 */
public final class ReportFile {

    private final long jobId;
    private final String htmlPath;
    private final String csvPath;
    private final String html;
    private final Map<String, String> normalizeReport;

    /**
     * 构造。
     *
     * @param jobId           作业 ID
     * @param htmlPath        HTML 文件路径（可 null，测试/内存场景）
     * @param csvPath         CSV 文件路径（可 null）
     * @param html            HTML 内容
     * @param normalizeReport 归一化规则快照（键值视图）
     */
    public ReportFile(long jobId, String htmlPath, String csvPath, String html,
                      Map<String, String> normalizeReport) {
        this.jobId = jobId;
        this.htmlPath = htmlPath;
        this.csvPath = csvPath;
        this.html = html == null ? "" : html;
        this.normalizeReport = normalizeReport == null ? Map.of() : Map.copyOf(normalizeReport);
    }

    /** @return 作业 ID */
    public long getJobId() {
        return jobId;
    }

    /** @return HTML 路径 */
    public String getHtmlPath() {
        return htmlPath;
    }

    /** @return CSV 路径 */
    public String getCsvPath() {
        return csvPath;
    }

    /** @return HTML 内容 */
    public String getHtml() {
        return html;
    }

    /** @return 归一化规则快照 */
    public Map<String, String> getNormalizeReport() {
        return normalizeReport;
    }

    @Override
    public String toString() {
        return "ReportFile[job=" + jobId + ",html=" + htmlPath + ",csv=" + csvPath + "]";
    }
}
