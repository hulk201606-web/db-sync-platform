package com.dbsync.cdc;

/**
 * 单条变更的应用结果（不可变）。
 *
 * <p>// ADDED: 04 文档给出 {@code UpsertApplier.apply} 返回 {@code ApplyResult} 但未定义该类。
 * 三种结果必须可区分：真的写了（applied）、按策略故意不写（skipped）、写失败（failed）——
 * 只有第一种才能计入"成功应用"，否则 Gate 的 {@code DUAL_WRITE_FAIL_RATE} 会被"跳过"刷低。
 */
public final class ApplyResult {

    private final boolean applied;
    private final boolean skipped;
    private final int rowsAffected;
    private final String conflictAction;
    private final String message;

    /**
     * 构造。
     *
     * @param applied        是否真的写过数据
     * @param skipped        是否按冲突策略主动跳过
     * @param rowsAffected   影响行数
     * @param conflictAction 冲突动作（写回 {@code sync_change_log.conflict_action}）
     * @param message        说明
     */
    private ApplyResult(boolean applied, boolean skipped, int rowsAffected, String conflictAction,
                        String message) {
        this.applied = applied;
        this.skipped = skipped;
        this.rowsAffected = rowsAffected;
        this.conflictAction = conflictAction;
        this.message = message;
    }

    /**
     * 已应用。
     *
     * @param rowsAffected 影响行数
     * @return 结果
     */
    public static ApplyResult applied(int rowsAffected) {
        return new ApplyResult(true, false, rowsAffected, "APPLIED", "已应用");
    }

    /**
     * 按冲突策略跳过。
     *
     * @param action 动作标识（如 {@code TARGET_WINS_IGNORED}）
     * @param reason 原因
     * @return 结果
     */
    public static ApplyResult skipped(String action, String reason) {
        return new ApplyResult(false, true, 0, action, reason);
    }

    /**
     * 失败。
     *
     * @param reason 失败原因
     * @return 结果
     */
    public static ApplyResult failed(String reason) {
        return new ApplyResult(false, false, 0, "FAILED", reason);
    }

    /** @return true 表示本次确实写了数据 */
    public boolean isApplied() {
        return applied;
    }

    /** @return true 表示按策略跳过 */
    public boolean isSkipped() {
        return skipped;
    }

    /** @return 影响行数 */
    public int getRowsAffected() {
        return rowsAffected;
    }

    /** @return 冲突动作 */
    public String getConflictAction() {
        return conflictAction;
    }

    /** @return 说明 */
    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "ApplyResult[" + conflictAction + ",rows=" + rowsAffected + "," + message + "]";
    }
}
