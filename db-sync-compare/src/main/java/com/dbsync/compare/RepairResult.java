package com.dbsync.compare;

import java.util.ArrayList;
import java.util.List;

/**
 * 修复结果（// ADDED：04 文档 T36 的 {@code DiffRepairer} 返回 {@code RepairResult}，
 * 但 common 模型清单里没有该类；修复结果是纯过程产物，不属于跨模块契约，故放在本模块）。
 *
 * <p>不可变。
 */
public final class RepairResult {

    private final long jobId;
    private final int total;
    private final int applied;
    private final int skipped;
    private final int failed;
    private final String backupTable;
    private final List<Long> failedIds;
    private final List<String> messages;

    /**
     * 构造。
     *
     * @param jobId       作业 ID
     * @param total       本次请求处理的差异数
     * @param applied     成功数
     * @param skipped     跳过数（如"仅目标端有"的行无法向目标回放）
     * @param failed      失败数
     * @param backupTable 备份表名（反向修复时非空）
     * @param failedIds   失败的差异 ID
     * @param messages    逐条说明（失败原因等）
     */
    public RepairResult(long jobId, int total, int applied, int skipped, int failed,
                        String backupTable, List<Long> failedIds, List<String> messages) {
        this.jobId = jobId;
        this.total = total;
        this.applied = applied;
        this.skipped = skipped;
        this.failed = failed;
        this.backupTable = backupTable;
        this.failedIds = List.copyOf(failedIds == null ? List.of() : failedIds);
        this.messages = List.copyOf(messages == null ? List.of() : messages);
    }

    /**
     * 全失败的空结果。
     *
     * @param jobId 作业 ID
     * @param why   原因
     * @return 结果
     */
    public static RepairResult failed(long jobId, String why) {
        List<String> messages = new ArrayList<String>();
        messages.add(why);
        return new RepairResult(jobId, 0, 0, 0, 0, null, List.<Long>of(), messages);
    }

    /** @return 作业 ID */
    public long getJobId() {
        return jobId;
    }

    /** @return 请求处理的差异数 */
    public int getTotal() {
        return total;
    }

    /** @return 成功数 */
    public int getApplied() {
        return applied;
    }

    /** @return 跳过数 */
    public int getSkipped() {
        return skipped;
    }

    /** @return 失败数 */
    public int getFailed() {
        return failed;
    }

    /** @return 备份表名（可 null） */
    public String getBackupTable() {
        return backupTable;
    }

    /** @return 失败的差异 ID（不可变） */
    public List<Long> getFailedIds() {
        return failedIds;
    }

    /** @return 逐条说明（不可变） */
    public List<String> getMessages() {
        return messages;
    }

    /**
     * @return true 表示全部成功（无失败）
     */
    public boolean isSuccess() {
        return failed == 0;
    }

    @Override
    public String toString() {
        return "RepairResult[job=" + jobId + ",applied=" + applied + ",failed=" + failed
                + ",skipped=" + skipped + "]";
    }
}
