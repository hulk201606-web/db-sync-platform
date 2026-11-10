package com.dbsync.common.enums;

/**
 * 变更队列条目状态。
 *
 * <p>水位线语义依赖本枚举：{@code safeSeq = MIN(seq) - 1 WHERE status='NEW'}，
 * 绝不能用 {@code MAX(已应用)}（会跳过空洞导致静默丢增量，架构 I4）。
 */
public enum ChangeStatus {
    /** 未消费。 */
    NEW,
    /** 已应用。 */
    APPLIED,
    /** 被冲突策略跳过。 */
    SKIPPED,
    /** 应用失败（可重试）。 */
    FAILED,
    /** 死信（重试耗尽）。 */
    DEAD;

    /**
     * @return true 表示已处理完（不再被 poll 取出）
     */
    public boolean isSettled() {
        return this == APPLIED || this == SKIPPED;
    }
}
