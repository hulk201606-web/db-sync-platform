package com.dbsync.common.model;

import java.time.Instant;
import java.util.Map;

/**
 * 比对报告（不可变）。{@code normalizeReport} 是"忽略/归一化规则实际生效"区块的键值视图，
 * 必须完整承载 {@code NormalizeOptions} 快照（CMP-05 不得静默忽略）。
 */
public final class CompareReport {

    private final long jobId;
    private final CompareVerdictSnapshot verdict;
    private final Map<String, String> normalizeReport;
    private final String html;
    private final String csvPath;
    private final Instant generatedAt;

    /**
     * 构造。
     *
     * @param jobId           作业 ID
     * @param verdict         结论快照
     * @param normalizeReport 归一化规则报告
     * @param html            HTML 内容
     * @param csvPath         CSV 文件路径
     * @param generatedAt     生成时间
     */
    public CompareReport(long jobId, CompareVerdictSnapshot verdict, Map<String, String> normalizeReport,
                         String html, String csvPath, Instant generatedAt) {
        this.jobId = jobId;
        this.verdict = verdict;
        this.normalizeReport = normalizeReport == null ? Map.of() : Map.copyOf(normalizeReport);
        this.html = html;
        this.csvPath = csvPath;
        this.generatedAt = generatedAt;
    }

    /** @return 作业 ID */
    public long getJobId() {
        return jobId;
    }

    /** @return 结论快照 */
    public CompareVerdictSnapshot getVerdict() {
        return verdict;
    }

    /** @return 归一化规则报告 */
    public Map<String, String> getNormalizeReport() {
        return normalizeReport;
    }

    /** @return HTML 内容 */
    public String getHtml() {
        return html;
    }

    /** @return CSV 路径 */
    public String getCsvPath() {
        return csvPath;
    }

    /** @return 生成时间 */
    public Instant getGeneratedAt() {
        return generatedAt;
    }
}
