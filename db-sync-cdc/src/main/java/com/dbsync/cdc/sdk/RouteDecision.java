package com.dbsync.cdc.sdk;

/**
 * 单次业务访问的路由决策结果（不变量：三个布尔值互斥组合已收敛，见 {@link #consistent()}）。
 *
 * <p>字段语义（业务方通常只需要看这三个）：
 * <ul>
 *   <li>{@code readTarget}：本次读是否走<b>目标库</b>（false = 读源库）；</li>
 *   <li>{@code writeTarget}：本次写是否要写<b>目标库</b>；</li>
 *   <li>{@code writeSource}：本次写是否要写<b>源库</b>（false 表示已停写源库，只在读切完成后的终态出现）。</li>
 * </ul>
 *
 * <p>为什么把"写源 / 写目标"拆成两个布尔而不是一个枚举：灰度双写阶段是"必然写源 + 部分写目标"，
 * 用枚举表达会漏掉 "BOTH" 这个第四态；三个布尔能直接映射到业务侧的
 * "if (decision.writeSource()) {...} if (decision.writeTarget()) {...}" 两段代码。
 *
 * <p>不可变、线程安全。
 */
public final class RouteDecision {

    /** 业务查不到快照时的兜底决策：只读写源库（最保守、永远可用的选择）。 */
    private static final RouteDecision SOURCE_ONLY = new RouteDecision(false, false, true);

    private final boolean readTarget;
    private final boolean writeTarget;
    private final boolean writeSource;

    /**
     * 构造。
     *
     * @param readTarget  读是否走目标库
     * @param writeTarget 写是否走目标库
     * @param writeSource 写是否走源库
     */
    public RouteDecision(boolean readTarget, boolean writeTarget, boolean writeSource) {
        this.readTarget = readTarget;
        this.writeTarget = writeTarget;
        this.writeSource = writeSource;
    }

    /**
     * 兜底决策：全走源库。
     *
     * @return 决策（读源、写源、不写目标）
     */
    public static RouteDecision sourceOnly() {
        return SOURCE_ONLY;
    }

    /** @return 读是否走目标库 */
    public boolean readTarget() {
        return readTarget;
    }

    /** @return 写是否走目标库 */
    public boolean writeTarget() {
        return writeTarget;
    }

    /** @return 写是否走源库 */
    public boolean writeSource() {
        return writeSource;
    }

    /**
     * 决策是否"可用"（至少有一个写目标，否则这次写会无处可去）。
     *
     * @return true 表示配置合理
     */
    public boolean consistent() {
        return writeSource || writeTarget;
    }

    @Override
    public String toString() {
        return "RouteDecision[read=" + (readTarget ? "TARGET" : "SOURCE") + ",write="
                + (writeSource && writeTarget ? "BOTH" : writeTarget ? "TARGET" : "SOURCE") + "]";
    }
}
