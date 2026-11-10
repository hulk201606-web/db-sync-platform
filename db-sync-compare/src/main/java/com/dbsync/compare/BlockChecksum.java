package com.dbsync.compare;

import com.dbsync.common.model.BlockResult;
import com.dbsync.common.model.PkRange;

/**
 * 单块校验和（三方：行数 + 数值列 SUM 串联哈希 + 行哈希异或聚合）。
 *
 * <p><b>为什么是三方而不是一方</b>：
 * <ul>
 *   <li>只比行数 → 抓不到"同数量但内容不同"；</li>
 *   <li>只比行哈希 → 抓不到"两行互换"（异或聚合对交换不敏感）；</li>
 *   <li>加上 SUM 哈希 → 能抓住"金额被改小、另一处被改大"的抵消型篡改。</li>
 * </ul>
 * 三方全等才判定块一致，任何一个不等即下钻。
 *
 * <p>本类不可变，可安全跨线程传递。
 */
public final class BlockChecksum {

    private final long blockId;
    private final int blockNo;
    private final long tableMappingId;
    private final PkRange range;
    private final long count;
    private final String sumHash;
    private final String rowHash;

    /**
     * 构造。
     *
     * @param blockId        块 ID（未落库时 0）
     * @param blockNo        块号（-1 表示未分配）
     * @param tableMappingId 表映射 ID
     * @param range          区间
     * @param count          行数
     * @param sumHash        数值列 SUM 串联哈希
     * @param rowHash        行哈希异或聚合（十六进制）
     */
    public BlockChecksum(long blockId, int blockNo, long tableMappingId, PkRange range, long count,
                         String sumHash, String rowHash) {
        this.blockId = blockId;
        this.blockNo = blockNo;
        this.tableMappingId = tableMappingId;
        this.range = range;
        this.count = count;
        this.sumHash = sumHash == null ? "" : sumHash;
        this.rowHash = rowHash == null ? "" : rowHash;
    }

    /**
     * 附加身份信息（引擎分配块号/块 ID 后调用）。
     *
     * @param newBlockId   块 ID
     * @param newBlockNo   块号
     * @param newMappingId 表映射 ID
     * @return 新实例
     */
    public BlockChecksum withIdentity(long newBlockId, int newBlockNo, long newMappingId) {
        return new BlockChecksum(newBlockId, newBlockNo, newMappingId, range, count, sumHash, rowHash);
    }

    /**
     * 三方比较。
     *
     * @param other 另一侧（目标端）校验和
     * @return true 表示行数、SUM 哈希、行哈希全部相等
     */
    public boolean matches(BlockChecksum other) {
        if (other == null) {
            return false;
        }
        return count == other.count && sumHash.equals(other.sumHash) && rowHash.equals(other.rowHash);
    }

    /**
     * 组装为对外的块结果。
     *
     * @param target   目标端校验和
     * @param matched  是否一致
     * @param diffRows 下钻得到的差异行数（未下钻填 0）
     * @return 块结果
     */
    public BlockResult toBlockResult(BlockChecksum target, boolean matched, int diffRows) {
        BlockChecksum t = target == null
                ? new BlockChecksum(blockId, blockNo, tableMappingId, range, 0L, "", "") : target;
        return new BlockResult(blockId, blockNo, tableMappingId, range, count, t.count, sumHash,
                t.sumHash, rowHash, t.rowHash, matched, diffRows);
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

    /** @return 区间 */
    public PkRange getRange() {
        return range;
    }

    /** @return 行数 */
    public long getCount() {
        return count;
    }

    /** @return SUM 串联哈希 */
    public String getSumHash() {
        return sumHash;
    }

    /** @return 行哈希聚合 */
    public String getRowHash() {
        return rowHash;
    }

    @Override
    public String toString() {
        return "BlockChecksum[" + range + ",count=" + count + ",rowHash=" + rowHash + "]";
    }
}
