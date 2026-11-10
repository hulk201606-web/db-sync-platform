package com.dbsync.common.model;

import java.util.Objects;

/**
 * 列元数据（不可变）。由 {@code MetadataReader} 一次填充完整。
 */
public final class ColumnMeta {

    private final String name;
    private final int ordinal;
    private final String typeName;
    private final int jdbcType;
    private final int size;
    private final int decimalDigits;
    private final boolean nullable;
    private final String defaultValue;
    private final String comment;
    private final boolean autoIncrement;
    private final boolean unsigned;
    private final boolean lob;

    private ColumnMeta(Builder b) {
        this.name = Objects.requireNonNull(b.name, "列名不可空");
        this.ordinal = b.ordinal;
        this.typeName = b.typeName == null ? "VARCHAR" : b.typeName.toUpperCase(java.util.Locale.ROOT);
        this.jdbcType = b.jdbcType;
        this.size = b.size;
        this.decimalDigits = b.decimalDigits;
        this.nullable = b.nullable;
        this.defaultValue = b.defaultValue;
        this.comment = b.comment;
        this.autoIncrement = b.autoIncrement;
        this.unsigned = b.unsigned;
        this.lob = b.lob;
    }

    /**
     * 创建 Builder。
     *
     * @param name    列名
     * @param ordinal 物理序号（从 1 开始）
     * @param typeName 类型名（如 {@code NUMBER}）
     * @return builder
     */
    public static Builder builder(String name, int ordinal, String typeName) {
        return new Builder(name, ordinal, typeName);
    }

    /** @return 列名 */
    public String getName() {
        return name;
    }

    /** @return 物理序号 */
    public int getOrdinal() {
        return ordinal;
    }

    /** @return 类型名（大写） */
    public String getTypeName() {
        return typeName;
    }

    /** @return JDBC 类型码 */
    public int getJdbcType() {
        return jdbcType;
    }

    /** @return 长度/精度 */
    public int getSize() {
        return size;
    }

    /** @return 小数位 */
    public int getDecimalDigits() {
        return decimalDigits;
    }

    /** @return 是否可空 */
    public boolean isNullable() {
        return nullable;
    }

    /** @return 默认值表达式，可能为 null */
    public String getDefaultValue() {
        return defaultValue;
    }

    /** @return 注释，可能为 null */
    public String getComment() {
        return comment;
    }

    /** @return 是否自增/标识列 */
    public boolean isAutoIncrement() {
        return autoIncrement;
    }

    /** @return 是否无符号（MySQL） */
    public boolean isUnsigned() {
        return unsigned;
    }

    /** @return 是否大字段（CLOB/BLOB/TEXT/BYTEA 等） */
    public boolean isLob() {
        return lob;
    }

    /**
     * 完整类型串，如 {@code NUMBER(19,0)}、{@code VARCHAR(64)}。
     *
     * @return 类型串
     */
    public String typeWithSize() {
        if (size <= 0) {
            return typeName;
        }
        if (decimalDigits > 0) {
            return typeName + "(" + size + "," + decimalDigits + ")";
        }
        return typeName + "(" + size + ")";
    }

    @Override
    public String toString() {
        return name + " " + typeWithSize() + (nullable ? "" : " NOT NULL");
    }

    /** 可变 Builder，避免 12 参数构造器。 */
    public static final class Builder {
        private final String name;
        private final int ordinal;
        private final String typeName;
        private int jdbcType;
        private int size;
        private int decimalDigits;
        private boolean nullable = true;
        private String defaultValue;
        private String comment;
        private boolean autoIncrement;
        private boolean unsigned;
        private boolean lob;

        private Builder(String name, int ordinal, String typeName) {
            this.name = name;
            this.ordinal = ordinal;
            this.typeName = typeName;
        }

        /**
         * @param v JDBC 类型码
         * @return this
         */
        public Builder jdbcType(int v) {
            this.jdbcType = v;
            return this;
        }

        /**
         * @param v 长度/精度
         * @return this
         */
        public Builder size(int v) {
            this.size = v;
            return this;
        }

        /**
         * @param v 小数位
         * @return this
         */
        public Builder decimalDigits(int v) {
            this.decimalDigits = v;
            return this;
        }

        /**
         * @param v 是否可空
         * @return this
         */
        public Builder nullable(boolean v) {
            this.nullable = v;
            return this;
        }

        /**
         * @param v 默认值
         * @return this
         */
        public Builder defaultValue(String v) {
            this.defaultValue = v;
            return this;
        }

        /**
         * @param v 注释
         * @return this
         */
        public Builder comment(String v) {
            this.comment = v;
            return this;
        }

        /**
         * @param v 是否自增
         * @return this
         */
        public Builder autoIncrement(boolean v) {
            this.autoIncrement = v;
            return this;
        }

        /**
         * @param v 是否无符号
         * @return this
         */
        public Builder unsigned(boolean v) {
            this.unsigned = v;
            return this;
        }

        /**
         * @param v 是否大字段
         * @return this
         */
        public Builder lob(boolean v) {
            this.lob = v;
            return this;
        }

        /**
         * @return 不可变列元数据
         */
        public ColumnMeta build() {
            return new ColumnMeta(this);
        }
    }
}
