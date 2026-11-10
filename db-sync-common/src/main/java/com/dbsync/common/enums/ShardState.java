package com.dbsync.common.enums;

/**
 * 分片状态机（架构 §9.3）。
 *
 * <p>{@code RUNNING → PENDING}（租约过期被回收）是"进程宕机"的唯一表达方式，
 * 因此 {@code LeaseReaperJob} 与 {@code claimOne} 必须使用同一判据
 * {@code lease_expire_at < now(3)}，否则会出现死区。
 */
public enum ShardState {

    /** 待抢占。 */
    PENDING,
    /** 已抢占（持有租约，未开始读）。 */
    CLAIMED,
    /** 运行中。 */
    RUNNING,
    /** 成功（区间读完且 checkpoint 已推进）。 */
    SUCCESS,
    /** 失败（可重试）。 */
    FAILED,
    /** 重试耗尽（需人工重放）。 */
    DEAD,
    /** 任务暂停导致挂起。 */
    PAUSED;

    /**
     * @return true 表示终态
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == DEAD;
    }

    /**
     * @return true 表示可被抢占（含过期租约回收后的状态）
     */
    public boolean isClaimable() {
        return this == PENDING || this == FAILED || this == PAUSED;
    }

    /**
     * @return true 表示持有租约中（需要心跳与续租）
     */
    public boolean isLeaseHeld() {
        return this == CLAIMED || this == RUNNING;
    }
}
