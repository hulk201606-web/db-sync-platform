package com.dbsync.common.model;

/**
 * 分块比对结果（不可变）。{@code matched=false} 的块才会被下钻。
 */
public final class BlockResult {

    private final long blockId;
    private final int blockNo;
    private final long tableMappingId;
    private final PkRange range;
    private final long srcCount;
    private final long tgtCount;
    private final String srcSumHash;
    private final String tgtSumHash;
    private final String srcRowHash;
    private final String tgtRowHash;
    private final boolean matched;
    private final int diffRows;

    /**
     * 构造。
     *
     * @param blockId        块 ID
     * @param blockNo        块号
     * @param tableMappingId 表映射 ID
     * @param range          块区间
     * @param srcCount       源行数
     * @param tgtCount       目标行数
     * @param srcSumHash     源数值列 SUM 串联哈希
     * @param tgtSumHash     目标数值列 SUM 串联哈希
     * @param srcRowHash     源行哈希聚合
     * @param tgtRowHash     目标行哈希聚合
     * @param matched        是否一致
     * @param diffRows       下钻后的差异行数
     */
    public BlockResult(long blockId, int blockNo, long tableMappingId, PkRange range,
                       long srcCount, long tgtCount, String srcSumHash, String tgtSumHash,
                       String srcRowHash, String tgtRowHash, boolean matched, int diffRows) {
        this.blockId = blockId;
        this.blockNo = blockNo;
        this.tableMappingId = tableMappingId;
        this.range = range;
        this.srcCount = srcCount;
        this.tgtCount = tgtCount;
        this.srcSumHash = srcSumHash;
        this.tgtSumHash = tgtSumHash;
        this.srcRowHash = srcRowHash;
        this.tgtRowHash = tgtRowHash;
        this.matched = matched;
        this.diffRows = diffRows;
    }

    /** @return 块 ID */
    public long getBlockId() {
        return blockId;
    }

    /** @return 块号 */
    public int getBlockNo() {
        return blockNo;
    }

    /** @return 表映射 ID */
    public long getTableMappingId() {
        return tableMappingId;
    }

    /** @return 块区间 */
    public PkRange getRange() {
        return range;
    }

    /** @return 源行数 */
    public long getSrcCount() {
        return srcCount;
    }

    /** @return 目标行数 */
    public long getTgtCount() {
        return tgtCount;
    }

    /** @return 源 SUM 哈希 */
    public String getSrcSumHash() {
        return srcSumHash;
    }

    /** @return 目标 SUM 哈希 */
    public String getTgtSumHash() {
        return tgtSumHash;
    }

    /** @return 源行哈希 */
    public String getSrcRowHash() {
        return srcRowHash;
    }

    /** @return 目标行哈希 */
    public String getTgtRowHash() {
        return tgtRowHash;
    }

    /** @return 是否一致 */
    public boolean isMatched() {
        return matched;
    }

    /** @return 下钻后的差异行数 */
    public int getDiffRows() {
        return diffRows;
    }

    /**
     * @return 返回带下钻结果的新实例
     * @param rows 下钻得到的差异行数
     */
    public BlockResult drilled(int rows) {
        return new BlockResult(blockId, blockNo, tableMappingId, range, srcCount, tgtCount,
                srcSumHash, tgtSumHash, srcRowHash, tgtRowHash, false, rows);
    }

    @Override
    public String toString() {
        return "Block#" + blockNo + "[matched=" + matched + ",diff=" + diffRows + "]";
    }
}
