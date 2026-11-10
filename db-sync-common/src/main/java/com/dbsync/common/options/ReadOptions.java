package com.dbsync.common.options;

/**
 * 读取选项（不可变）。
 *
 * <p>{@code lobAware=true} 时调用方必须把批大小降到 1 行（架构 §13.3 内存风险 2）。
 */
public final class ReadOptions {

    private final int batchSize;
    private final int fetchSize;
    private final boolean lobAware;

    /**
     * 构造。
     *
     * @param batchSize 批大小
     * @param fetchSize 游标 fetchSize
     * @param lobAware  是否含大字段（含则批大小强制为 1）
     */
    public ReadOptions(int batchSize, int fetchSize, boolean lobAware) {
        this.batchSize = batchSize <= 0 ? 1000 : batchSize;
        this.fetchSize = fetchSize <= 0 ? 1000 : fetchSize;
        this.lobAware = lobAware;
    }

    /**
     * @param batchSize 批大小
     * @return 默认选项
     */
    public static ReadOptions of(int batchSize) {
        return new ReadOptions(batchSize, batchSize, false);
    }

    /**
     * 含 LOB 表的安全选项。
     *
     * @return 批大小 1 的选项
     */
    public static ReadOptions lobSafe() {
        return new ReadOptions(1, 1, true);
    }

    /** @return 批大小 */
    public int getBatchSize() {
        return batchSize;
    }

    /** @return fetchSize */
    public int getFetchSize() {
        return fetchSize;
    }

    /** @return 是否含大字段 */
    public boolean isLobAware() {
        return lobAware;
    }

    /**
     * 生效批大小：含 LOB 时恒为 1。
     *
     * @return 生效批大小
     */
    public int effectiveBatchSize() {
        return lobAware ? 1 : batchSize;
    }
}
