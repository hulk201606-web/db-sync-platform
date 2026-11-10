package com.dbsync.migration;

/**
 * 行数差异（不可变）。
 *
 * <p>// ADDED: 04 文档的 T26 签名中出现 {@code List<RowCountDiff>} 但未给定义，本类为其最小定义。
 *
 * <p>语义：{@code diff = targetRows - sourceRows}。
 * 只报告 {@code diff != 0} 的表，因此"空列表"就等价于"全部一致"——
 * 调用方不需要再判断一遍。
 */
public final class RowCountDiff {

    private final long tableMappingId;
    private final String sourceTable;
    private final String targetTable;
    private final long sourceRows;
    private final long targetRows;

    /**
     * 构造。
     *
     * @param tableMappingId 表映射 ID
     * @param sourceTable    源表全名
     * @param targetTable    目标表全名
     * @param sourceRows     源行数
     * @param targetRows     目标行数
     */
    public RowCountDiff(long tableMappingId, String sourceTable, String targetTable,
                        long sourceRows, long targetRows) {
        this.tableMappingId = tableMappingId;
        this.sourceTable = sourceTable;
        this.targetTable = targetTable;
        this.sourceRows = sourceRows;
        this.targetRows = targetRows;
    }

    /** @return 表映射 ID */
    public long getTableMappingId() {
        return tableMappingId;
    }

    /** @return 源表全名 */
    public String getSourceTable() {
        return sourceTable;
    }

    /** @return 目标表全名 */
    public String getTargetTable() {
        return targetTable;
    }

    /** @return 源行数 */
    public long getSourceRows() {
        return sourceRows;
    }

    /** @return 目标行数 */
    public long getTargetRows() {
        return targetRows;
    }

    /**
     * @return 差异（目标 - 源）；负数表示目标缺行，正数表示目标多行
     */
    public long diff() {
        return targetRows - sourceRows;
    }

    /**
     * @return true 表示两侧行数一致
     */
    public boolean isMatch() {
        return sourceRows == targetRows;
    }

    @Override
    public String toString() {
        return "RowCountDiff[" + sourceTable + "=" + sourceRows + " vs " + targetTable + "="
                + targetRows + ",diff=" + diff() + "]";
    }
}
