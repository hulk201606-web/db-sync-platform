package com.dbsync.common.model;

/**
 * 单个表映射的行数汇总（{@code sync_shard} 按 {@code table_mapping_id} 分组聚合的结果）。
 *
 * <p><b>为什么在库里算</b>：一个 8 亿行大表可能切出 400+ 分片，
 * 若把全部分片查回内存再循环求和，一次大盘轮询就要几十 KB~MB 的对象分配，
 * 且随分片数线性恶化；{@code SUM(...) GROUP BY table_mapping_id} 由数据库一次算完。
 *
 * @see com.dbsync.metadata.dao.ShardDao#sumRowsGroupByMapping(long)
 */
public final class ShardRowSums {

    /** 已提交行数合计。 */
    private final long committedRows;

    /** 源侧行数合计（{@code source_rows} 默认 -1 表示未知，聚合后可能是负数）。 */
    private final long sourceRows;

    /**
     * 构造。
     *
     * @param committedRows 已提交行数合计
     * @param sourceRows    源侧行数合计
     */
    public ShardRowSums(long committedRows, long sourceRows) {
        this.committedRows = committedRows;
        this.sourceRows = sourceRows;
    }

    /** @return 已提交行数合计 */
    public long getCommittedRows() {
        return committedRows;
    }

    /** @return 源侧行数合计（≤0 表示未知） */
    public long getSourceRows() {
        return sourceRows;
    }

    @Override
    public String toString() {
        return "ShardRowSums[committed=" + committedRows + ",source=" + sourceRows + "]";
    }
}
