package com.dbsync.common.options;

/**
 * 写入选项（不可变）。
 */
public final class WriteOptions {

    private final int batchSize;
    private final boolean useUpsert;
    private final boolean streamLob;

    /**
     * 构造。
     *
     * @param batchSize 批大小（仅用于日志与分批提示，实际由调用方控制）
     * @param useUpsert 是否幂等 upsert
     * @param streamLob LOB 是否用流式绑定
     */
    public WriteOptions(int batchSize, boolean useUpsert, boolean streamLob) {
        this.batchSize = batchSize <= 0 ? 1000 : batchSize;
        this.useUpsert = useUpsert;
        this.streamLob = streamLob;
    }

    /**
     * @return 默认 upsert 选项
     */
    public static WriteOptions upsert() {
        return new WriteOptions(1000, true, true);
    }

    /**
     * @return 纯 insert 选项
     */
    public static WriteOptions insertOnly() {
        return new WriteOptions(1000, false, true);
    }

    /** @return 批大小 */
    public int getBatchSize() {
        return batchSize;
    }

    /** @return 是否 upsert */
    public boolean isUseUpsert() {
        return useUpsert;
    }

    /** @return 是否流式绑定 LOB */
    public boolean isStreamLob() {
        return streamLob;
    }
}
