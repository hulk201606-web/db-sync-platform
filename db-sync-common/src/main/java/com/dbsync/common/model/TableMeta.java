package com.dbsync.common.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 表元数据（不可变）。构造时校验"主键列必须存在于列集合中"。
 */
public final class TableMeta {

    private final String schema;
    private final String tableName;
    private final List<ColumnMeta> columns;
    private final PrimaryKeyMeta primaryKey;
    private final List<IndexMeta> indexes;
    private final long estimatedRows;
    private final String comment;

    /**
     * 构造。
     *
     * @param schema        所属 schema，可空
     * @param tableName     表名，不可空
     * @param columns       列（物理顺序），不可空
     * @param primaryKey    主键，null 视为无主键
     * @param indexes       索引，null 视为空
     * @param estimatedRows 估算行数，-1 表示未知
     * @param comment       表注释，可空
     * @throws IllegalArgumentException 当主键列不在列集合中
     */
    public TableMeta(String schema, String tableName, List<ColumnMeta> columns,
                     PrimaryKeyMeta primaryKey, List<IndexMeta> indexes,
                     long estimatedRows, String comment) {
        this.schema = schema;
        this.tableName = Objects.requireNonNull(tableName, "表名不可空");
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        this.primaryKey = primaryKey == null ? PrimaryKeyMeta.NONE : primaryKey;
        this.indexes = List.copyOf(indexes == null ? List.of() : indexes);
        this.estimatedRows = estimatedRows;
        this.comment = comment;
        for (String pk : this.primaryKey.getColumns()) {
            if (column(pk).isEmpty()) {
                throw new IllegalArgumentException(
                        "主键列不在列集合中：" + tableName + "." + pk);
            }
        }
    }

    /**
     * 便捷工厂（无索引、无注释）。
     *
     * @param schema      schema
     * @param tableName   表名
     * @param columns     列
     * @param primaryKey  主键
     * @return 表元数据
     */
    public static TableMeta of(String schema, String tableName, List<ColumnMeta> columns,
                               PrimaryKeyMeta primaryKey) {
        return new TableMeta(schema, tableName, columns, primaryKey, List.of(), -1L, null);
    }

    /** @return schema，可能为 null */
    public String getSchema() {
        return schema;
    }

    /** @return 表名 */
    public String getTableName() {
        return tableName;
    }

    /** @return 不可变列列表 */
    public List<ColumnMeta> getColumns() {
        return columns;
    }

    /** @return 主键（可能为空主键） */
    public PrimaryKeyMeta getPrimaryKey() {
        return primaryKey;
    }

    /** @return 不可变索引列表 */
    public List<IndexMeta> getIndexes() {
        return indexes;
    }

    /** @return 估算行数，-1 未知 */
    public long getEstimatedRows() {
        return estimatedRows;
    }

    /** @return 表注释，可能为 null */
    public String getComment() {
        return comment;
    }

    /**
     * @return 主键列元数据（按主键列顺序）
     */
    public List<ColumnMeta> primaryKeyColumns() {
        List<ColumnMeta> list = new ArrayList<>();
        for (String name : primaryKey.getColumns()) {
            column(name).ifPresent(list::add);
        }
        return List.copyOf(list);
    }

    /**
     * @return 是否定义了主键
     */
    public boolean hasPrimaryKey() {
        return !primaryKey.isEmpty();
    }

    /**
     * @return 物理顺序的列名
     */
    public List<String> columnNames() {
        List<String> names = new ArrayList<>(columns.size());
        for (ColumnMeta c : columns) {
            names.add(c.getName());
        }
        return List.copyOf(names);
    }

    /**
     * @return 主键列名（有序）
     */
    public List<String> primaryKeyNames() {
        return primaryKey.getColumns();
    }

    /**
     * 按列名（忽略大小写）查找列。
     *
     * @param nameIgnoreCase 列名
     * @return 命中的列
     */
    public Optional<ColumnMeta> column(String nameIgnoreCase) {
        if (nameIgnoreCase == null) {
            return Optional.empty();
        }
        for (ColumnMeta c : columns) {
            if (c.getName().equalsIgnoreCase(nameIgnoreCase)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /**
     * @return 是否含大字段列（含 LOB 时迁移批次必须降到 1 行，架构 §13.3）
     */
    public boolean hasLobColumn() {
        for (ColumnMeta c : columns) {
            if (c.isLob()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 全限定名 {@code schema.table}（无 schema 时只返回表名）。
     *
     * @return 全限定名
     */
    public String qualifiedName() {
        return schema == null || schema.isBlank() ? tableName : schema + "." + tableName;
    }

    @Override
    public String toString() {
        return qualifiedName() + "(" + columns.size() + " cols)";
    }
}
