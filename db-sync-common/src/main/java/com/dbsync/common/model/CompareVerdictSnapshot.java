package com.dbsync.common.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 比对结论快照（供 Gate G1 消费，经 {@code CompareVerdictPort} 跨模块传递）。
 */
public final class CompareVerdictSnapshot {

    private final long jobId;
    private final long taskId;
    private final double passRate;
    private final long totalRows;
    private final long diffRows;
    private final String verdict;
    private final Instant finishedAt;
    private final Map<String, Object> normalizeSnapshot;

    /**
     * 构造。
     *
     * @param jobId             作业 ID
     * @param taskId            任务 ID
     * @param passRate          通过率
     * @param totalRows         总行数
     * @param diffRows          差异行数
     * @param verdict           结论字符串
     * @param finishedAt        完成时间
     * @param normalizeSnapshot 归一化规则快照
     */
    public CompareVerdictSnapshot(long jobId, long taskId, double passRate, long totalRows, long diffRows,
                                  String verdict, Instant finishedAt, Map<String, Object> normalizeSnapshot) {
        this.jobId = jobId;
        this.taskId = taskId;
        this.passRate = passRate;
        this.totalRows = totalRows;
        this.diffRows = diffRows;
        this.verdict = verdict;
        this.finishedAt = Objects.requireNonNull(finishedAt, "finishedAt");
        this.normalizeSnapshot = normalizeSnapshot == null ? Map.of() : Map.copyOf(normalizeSnapshot);
    }

    /** @return 作业 ID */
    public long getJobId() {
        return jobId;
    }

    /** @return 任务 ID */
    public long getTaskId() {
        return taskId;
    }

    /** @return 通过率 */
    public double getPassRate() {
        return passRate;
    }

    /** @return 总行数 */
    public long getTotalRows() {
        return totalRows;
    }

    /** @return 差异行数 */
    public long getDiffRows() {
        return diffRows;
    }

    /** @return 结论 */
    public String getVerdict() {
        return verdict;
    }

    /** @return 完成时间 */
    public Instant getFinishedAt() {
        return finishedAt;
    }

    /** @return 归一化规则快照 */
    public Map<String, Object> getNormalizeSnapshot() {
        return normalizeSnapshot;
    }

    /**
     * @return 结论是否可用于 Gate
     */
    public boolean isAcceptable() {
        return "PASS".equals(verdict) || "WARN".equals(verdict);
    }

    @Override
    public String toString() {
        return "Verdict[job=" + jobId + "," + verdict + ",passRate=" + passRate + "]";
    }
}
