package com.dbsync.common.options;

/**
 * 比对选项（不可变）。
 */
public final class CompareOptions {

    private final int blockRows;
    private final int parallelism;
    private final double passThreshold;
    private final NormalizeOptions normalize;
    private final boolean drillDown;

    /**
     * 构造。
     *
     * @param blockRows     块行数
     * @param parallelism   并发度
     * @param passThreshold 通过率阈值（0.9999）
     * @param normalize     归一化选项
     * @param drillDown     是否对差异块下钻
     */
    public CompareOptions(int blockRows, int parallelism, double passThreshold,
                          NormalizeOptions normalize, boolean drillDown) {
        this.blockRows = blockRows <= 0 ? 100_000 : blockRows;
        this.parallelism = parallelism <= 0 ? 8 : parallelism;
        this.passThreshold = passThreshold <= 0 ? 0.9999d : passThreshold;
        this.normalize = normalize == null ? NormalizeOptions.defaults() : normalize;
        this.drillDown = drillDown;
    }

    /**
     * @return 默认选项
     */
    public static CompareOptions defaults() {
        return new CompareOptions(100_000, 8, 0.9999d, NormalizeOptions.defaults(), true);
    }

    /** @return 块行数 */
    public int getBlockRows() {
        return blockRows;
    }

    /** @return 并发度 */
    public int getParallelism() {
        return parallelism;
    }

    /** @return 通过率阈值 */
    public double getPassThreshold() {
        return passThreshold;
    }

    /** @return 归一化选项 */
    public NormalizeOptions getNormalize() {
        return normalize;
    }

    /** @return 是否下钻 */
    public boolean isDrillDown() {
        return drillDown;
    }
}
