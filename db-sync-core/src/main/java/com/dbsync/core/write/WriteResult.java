package com.dbsync.core.write;

/**
 * 写结果（不可变）。
 *
 * <p>{@code committed} 是整个平台最重要的一个布尔值：
 * {@code CheckpointStore.save()} 用它在<b>代码层面</b>强制 I1 顺序
 * （先提交数据、再推进断点）。它<b>只</b>能通过 {@link #afterCommit(long, long)} 变成 true——
 * 也就是说，只有真正执行过 {@code commit()} 的调用方才有资格构造"已提交"结果，
 * 写库组件自己永远不会把 committed 置 true（它不持有事务）。
 */
public final class WriteResult {

    private final long rowsAffected;
    private final boolean committed;
    private final long durationMs;
    private final String failureReason;

    /**
     * 私有构造（只允许工厂方法创建，防止有人手写 {@code new WriteResult(n, true, ms)} 绕过守卫）。
     *
     * @param rowsAffected  影响行数
     * @param committed     是否已提交
     * @param durationMs    耗时
     * @param failureReason 失败原因（成功为 null）
     */
    private WriteResult(long rowsAffected, boolean committed, long durationMs,
                        String failureReason) {
        this.rowsAffected = rowsAffected;
        this.committed = committed;
        this.durationMs = durationMs;
        this.failureReason = failureReason;
    }

    /**
     * 写入完成但<b>尚未</b>提交（写库组件的返回值）。
     *
     * @param rowsAffected 影响行数
     * @param durationMs   耗时
     * @return 未提交结果
     */
    public static WriteResult beforeCommit(long rowsAffected, long durationMs) {
        return new WriteResult(rowsAffected, false, durationMs, null);
    }

    /**
     * 提交成功之后的结果（只有调用方执行完 {@code commit()} 才可调用）。
     *
     * @param rowsAffected 影响行数
     * @param durationMs   耗时
     * @return 已提交结果
     */
    public static WriteResult afterCommit(long rowsAffected, long durationMs) {
        return new WriteResult(rowsAffected, true, durationMs, null);
    }

    /**
     * 失败结果（committed 永远 false）。
     *
     * @param reason 失败原因
     * @return 失败结果
     */
    public static WriteResult failed(String reason) {
        return new WriteResult(0L, false, 0L, reason);
    }

    /** @return 影响行数 */
    public long rowsAffected() {
        return rowsAffected;
    }

    /** @return 是否已提交（CheckpointStore 的守卫条件） */
    public boolean committed() {
        return committed;
    }

    /** @return 耗时毫秒 */
    public long durationMs() {
        return durationMs;
    }

    /** @return 失败原因（成功为 null） */
    public String failureReason() {
        return failureReason;
    }

    @Override
    public String toString() {
        return "WriteResult[rows=" + rowsAffected + ",committed=" + committed + "," + durationMs
                + "ms]";
    }
}
