package com.dbsync.common.model;

import java.time.Instant;

/**
 * 组水位（不可变）。{@code safeSeq} 的语义是"连续已应用前缀"，
 * 必须由 {@code MIN(未应用 seq) - 1} 计算，绝不是 {@code MAX(已应用)}。
 */
public final class Watermark {

    private final long taskId;
    private final int groupNo;
    private final long safeSeq;
    private final Instant safeCommitTs;
    private final String consumerOwner;
    private final long lagMs;

    /**
     * 构造。
     *
     * @param taskId        任务 ID
     * @param groupNo       组号
     * @param safeSeq       连续前缀水位
     * @param safeCommitTs  水位对应的提交时间
     * @param consumerOwner 组独占消费者
     * @param lagMs         当前延迟毫秒
     */
    public Watermark(long taskId, int groupNo, long safeSeq, Instant safeCommitTs,
                     String consumerOwner, long lagMs) {
        this.taskId = taskId;
        this.groupNo = groupNo;
        this.safeSeq = safeSeq;
        this.safeCommitTs = safeCommitTs;
        this.consumerOwner = consumerOwner;
        this.lagMs = lagMs;
    }

    /** @return 任务 ID */
    public long getTaskId() {
        return taskId;
    }

    /** @return 组号 */
    public int getGroupNo() {
        return groupNo;
    }

    /** @return 安全序号 */
    public long getSafeSeq() {
        return safeSeq;
    }

    /** @return 安全提交时间 */
    public Instant getSafeCommitTs() {
        return safeCommitTs;
    }

    /** @return 消费者实例 ID */
    public String getConsumerOwner() {
        return consumerOwner;
    }

    /** @return 延迟毫秒 */
    public long getLagMs() {
        return lagMs;
    }

    @Override
    public String toString() {
        return "Watermark[task=" + taskId + ",g=" + groupNo + ",safeSeq=" + safeSeq + "]";
    }
}
