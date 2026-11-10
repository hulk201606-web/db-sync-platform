package com.dbsync.common.model;

import java.util.List;
import java.util.Objects;

/**
 * 索引元数据（不可变）。
 */
public final class IndexMeta {

    private final String name;
    private final List<String> columns;
    private final boolean unique;

    /**
     * 构造。
     *
     * @param name    索引名，不可空
     * @param columns 列名（按索引列顺序），不可空
     * @param unique  是否唯一索引
     */
    public IndexMeta(String name, List<String> columns, boolean unique) {
        this.name = Objects.requireNonNull(name, "索引名不可空");
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        this.unique = unique;
    }

    /** @return 索引名 */
    public String getName() {
        return name;
    }

    /** @return 不可变列名列表 */
    public List<String> getColumns() {
        return columns;
    }

    /** @return 是否唯一 */
    public boolean isUnique() {
        return unique;
    }

    @Override
    public String toString() {
        return (unique ? "UNIQUE " : "") + name + columns;
    }
}
