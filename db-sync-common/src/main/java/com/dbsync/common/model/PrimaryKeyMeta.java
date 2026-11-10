package com.dbsync.common.model;

import java.util.List;

/**
 * 主键元数据（不可变）。含约束名与有序列名；{@link #NONE} 表示无主键。
 */
public final class PrimaryKeyMeta {

    /** 无主键的占位实例。 */
    public static final PrimaryKeyMeta NONE = new PrimaryKeyMeta(null, List.of());

    private final String name;
    private final List<String> columns;

    /**
     * 构造。
     *
     * @param name    约束名，可空
     * @param columns 有序主键列名
     */
    public PrimaryKeyMeta(String name, List<String> columns) {
        this.name = name;
        this.columns = List.copyOf(columns == null ? List.of() : columns);
    }

    /** @return 约束名，可能为 null */
    public String getName() {
        return name;
    }

    /** @return 不可变主键列名列表 */
    public List<String> getColumns() {
        return columns;
    }

    /** @return true 表示无主键 */
    public boolean isEmpty() {
        return columns.isEmpty();
    }

    @Override
    public String toString() {
        return "PK" + columns;
    }
}
