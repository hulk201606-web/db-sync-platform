package com.dbsync.cdc;

/**
 * 冲突判定结果（不可变）。
 *
 * <p>// ADDED: 04 文档 T30 的 {@code ConflictResolver.resolve} 返回 {@code ConflictDecision}
 * 但未定义该类。之所以不复用 {@link ApplyResult}：判定阶段<b>还没有连接</b>去写库，
 * 把"要不要写"和"写的结果"混成一个类型，会让人误以为 resolve 也能写数据。
 */
public final class ConflictDecision {

    /** 源端优先动作。 */
    public static final String APPLY = "APPLIED";

    /** 目标端优先：已存在即忽略。 */
    public static final String TARGET_WINS_IGNORED = "TARGET_WINS_IGNORED";

    /** 时间戳更旧的变更被忽略。 */
    public static final String TIMESTAMP_OLDER_IGNORED = "TIMESTAMP_OLDER_IGNORED";

    private final boolean apply;
    private final String action;
    private final String reason;

    /**
     * 构造。
     *
     * @param apply  是否应用
     * @param action 动作标识
     * @param reason 原因
     */
    private ConflictDecision(boolean apply, String action, String reason) {
        this.apply = apply;
        this.action = action;
        this.reason = reason;
    }

    /**
     * 应用。
     *
     * @return 判定
     */
    public static ConflictDecision apply() {
        return new ConflictDecision(true, APPLY, "按策略应用");
    }

    /**
     * 跳过。
     *
     * @param action 动作标识
     * @param reason 原因
     * @return 判定
     */
    public static ConflictDecision skip(String action, String reason) {
        return new ConflictDecision(false, action, reason);
    }

    /** @return true 表示应当应用 */
    public boolean isApply() {
        return apply;
    }

    /** @return 动作标识（写回 {@code conflict_action}） */
    public String getAction() {
        return action;
    }

    /** @return 原因 */
    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "ConflictDecision[" + action + "," + reason + "]";
    }
}
