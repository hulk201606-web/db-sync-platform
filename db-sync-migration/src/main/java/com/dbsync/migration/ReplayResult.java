package com.dbsync.migration;

import com.dbsync.common.enums.DeadLetterStatus;

/**
 * 死信重放结果（不可变）。
 *
 * <p>// ADDED: 04 文档的签名里出现了 {@code ReplayResult} 但未给出定义，
 * 本类为其最小完整定义：既要有"成功/失败"，也要有"已经是终态、本次什么也没做"，
 * 否则重复点击重放会拿到一个无法区分的成功，运维会以为"又修了一条"。
 */
public final class ReplayResult {

    private final long deadLetterId;
    private final DeadLetterStatus status;
    private final boolean applied;
    private final boolean duplicate;
    private final int rowsAffected;
    private final String message;

    /**
     * 构造。
     *
     * @param deadLetterId 死信 ID
     * @param status       重放后状态
     * @param applied      本次是否真的应用了数据
     * @param duplicate    是否因"已经是终态"而跳过（幂等命中）
     * @param rowsAffected 影响行数
     * @param message      说明
     */
    private ReplayResult(long deadLetterId, DeadLetterStatus status, boolean applied,
                         boolean duplicate, int rowsAffected, String message) {
        this.deadLetterId = deadLetterId;
        this.status = status;
        this.applied = applied;
        this.duplicate = duplicate;
        this.rowsAffected = rowsAffected;
        this.message = message;
    }

    /**
     * 重放成功。
     *
     * @param deadLetterId 死信 ID
     * @param rowsAffected 影响行数
     * @return 结果
     */
    public static ReplayResult applied(long deadLetterId, int rowsAffected) {
        return new ReplayResult(deadLetterId, DeadLetterStatus.REPLAYED, true, false, rowsAffected,
                "重放成功");
    }

    /**
     * 幂等命中：之前已重放/已丢弃，本次不做任何事。
     *
     * @param deadLetterId 死信 ID
     * @param status       当前状态
     * @return 结果
     */
    public static ReplayResult duplicate(long deadLetterId, DeadLetterStatus status) {
        return new ReplayResult(deadLetterId, status, false, true, 0,
                "死信已是终态 " + status + "，本次重放跳过");
    }

    /**
     * 重放失败（已退回 OPEN）。
     *
     * @param deadLetterId 死信 ID
     * @param message      失败原因
     * @return 结果
     */
    public static ReplayResult failed(long deadLetterId, String message) {
        return new ReplayResult(deadLetterId, DeadLetterStatus.OPEN, false, false, 0, message);
    }

    /**
     * 并发重放中（另一个实例刚把它置为 REPLAYING）。
     *
     * @param deadLetterId 死信 ID
     * @return 结果
     */
    public static ReplayResult inProgress(long deadLetterId) {
        return new ReplayResult(deadLetterId, DeadLetterStatus.REPLAYING, false, true, 0,
                "死信正在被其他实例重放");
    }

    /** @return 死信 ID */
    public long getDeadLetterId() {
        return deadLetterId;
    }

    /** @return 重放后状态 */
    public DeadLetterStatus getStatus() {
        return status;
    }

    /** @return true 表示本次真的写了数据 */
    public boolean isApplied() {
        return applied;
    }

    /** @return true 表示因终态/并发而跳过 */
    public boolean isDuplicate() {
        return duplicate;
    }

    /** @return 影响行数 */
    public int getRowsAffected() {
        return rowsAffected;
    }

    /** @return 说明 */
    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "ReplayResult[dl=" + deadLetterId + "," + status + ",applied=" + applied
                + ",dup=" + duplicate + ",rows=" + rowsAffected + "]";
    }
}
