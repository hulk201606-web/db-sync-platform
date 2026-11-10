package com.dbsync.common.model;

import com.dbsync.common.enums.CompareVerdict;
import java.util.List;

/**
 * 比对结果汇总（不可变）。
 */
public final class CompareResult {

    private final long jobId;
    private final long taskId;
    private final int totalBlocks;
    private final int diffBlocks;
    private final long totalRows;
    private final long diffRows;
    private final CompareVerdict verdict;
    private final List<BlockResult> blocks;

    /**
     * 构造。
     *
     * @param jobId       作业 ID
     * @param taskId      任务 ID
     * @param totalBlocks 总块数
     * @param diffBlocks  差异块数
     * @param totalRows   总行数
     * @param diffRows    差异行数
     * @param verdict     结论
     * @param blocks      块明细
     */
    public CompareResult(long jobId, long taskId, int totalBlocks, int diffBlocks, long totalRows,
                         long diffRows, CompareVerdict verdict, List<BlockResult> blocks) {
        this.jobId = jobId;
        this.taskId = taskId;
        this.totalBlocks = totalBlocks;
        this.diffBlocks = diffBlocks;
        this.totalRows = totalRows;
        this.diffRows = diffRows;
        this.verdict = verdict;
        this.blocks = List.copyOf(blocks == null ? List.of() : blocks);
    }

    /**
     * 按阈值计算结论。
     *
     * @param jobId       作业 ID
     * @param taskId      任务 ID
     * @param totalRows   总行数
     * @param diffRows    差异行数
     * @param passRate    期望通过率（如 0.9999）
     * @return 结论
     */
    public static CompareVerdict verdictOf(long totalRows, long diffRows, double passRate) {
        if (totalRows <= 0) {
            return CompareVerdict.PASS;
        }
        double actual = (double) (totalRows - diffRows) / (double) totalRows;
        return actual >= passRate ? CompareVerdict.PASS
                : (actual >= passRate - 0.001 ? CompareVerdict.WARN : CompareVerdict.FAIL);
    }

    /** @return 作业 ID */
    public long getJobId() {
        return jobId;
    }

    /** @return 任务 ID */
    public long getTaskId() {
        return taskId;
    }

    /** @return 总块数 */
    public int getTotalBlocks() {
        return totalBlocks;
    }

    /** @return 差异块数 */
    public int getDiffBlocks() {
        return diffBlocks;
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
    public CompareVerdict getVerdict() {
        return verdict;
    }

    /** @return 不可变块明细 */
    public List<BlockResult> getBlocks() {
        return blocks;
    }

    /**
     * @return 通过率（0..1；总行数为 0 时返回 1.0）
     */
    public double passRate() {
        if (totalRows <= 0) {
            return 1.0d;
        }
        return (double) (totalRows - diffRows) / (double) totalRows;
    }

    @Override
    public String toString() {
        return "CompareResult[job=" + jobId + ",passRate=" + String.format("%.6f", passRate())
                + ",verdict=" + verdict + "]";
    }
}
