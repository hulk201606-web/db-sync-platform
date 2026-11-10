package com.dbsync.common.options;

/**
 * 分片规划选项（不可变）。
 */
public final class ShardPlanOptions {

    private final int targetShardRows;
    private final int minShards;
    private final int maxShards;
    private final int sampleBuckets;

    /**
     * 构造。
     *
     * @param targetShardRows 每分片目标行数
     * @param minShards       最少分片数
     * @param maxShards       最多分片数
     * @param sampleBuckets   分位采样桶数（≤8）
     */
    public ShardPlanOptions(int targetShardRows, int minShards, int maxShards, int sampleBuckets) {
        this.targetShardRows = targetShardRows <= 0 ? 2_000_000 : targetShardRows;
        this.minShards = Math.max(1, minShards);
        this.maxShards = Math.max(this.minShards, maxShards);
        this.sampleBuckets = Math.min(8, Math.max(1, sampleBuckets));
    }

    /**
     * @return 默认选项（200 万行/片，1..500 片）
     */
    public static ShardPlanOptions defaults() {
        return new ShardPlanOptions(2_000_000, 1, 500, 8);
    }

    /**
     * 便捷构造。
     *
     * @param targetShardRows 每片行数
     * @param maxShards       最大片数
     * @return 选项
     */
    public static ShardPlanOptions of(int targetShardRows, int maxShards) {
        return new ShardPlanOptions(targetShardRows, 1, maxShards, 8);
    }

    /** @return 每分片目标行数 */
    public int getTargetShardRows() {
        return targetShardRows;
    }

    /** @return 最少分片数 */
    public int getMinShards() {
        return minShards;
    }

    /** @return 最多分片数 */
    public int getMaxShards() {
        return maxShards;
    }

    /** @return 采样桶数 */
    public int getSampleBuckets() {
        return sampleBuckets;
    }
}
