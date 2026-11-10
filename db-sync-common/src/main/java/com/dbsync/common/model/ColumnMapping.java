package com.dbsync.common.model;

/**
 * 列映射（不可变）。{@code mappedType} 非空表示人工覆盖（需记录 {@code overrideBy}）。
 */
public final class ColumnMapping {

    private final String sourceColumn;
    private final String targetColumn;
    private final String sourceType;
    private final String targetType;
    private final String mappedType;
    private final String overrideBy;
    private final boolean primaryKey;
    private final boolean autoIncrement;
    private final int ordinal;

    /**
     * 构造。
     *
     * @param sourceColumn  源列名
     * @param targetColumn  目标列名
     * @param sourceType    源类型串
     * @param targetType    自动映射后的目标类型
     * @param mappedType    人工覆盖类型（可 null）
     * @param overrideBy    覆盖人（可 null）
     * @param primaryKey    是否主键列
     * @param autoIncrement 是否自增
     * @param ordinal       顺序
     */
    public ColumnMapping(String sourceColumn, String targetColumn, String sourceType, String targetType,
                         String mappedType, String overrideBy, boolean primaryKey,
                         boolean autoIncrement, int ordinal) {
        this.sourceColumn = sourceColumn;
        this.targetColumn = targetColumn;
        this.sourceType = sourceType;
        this.targetType = targetType;
        this.mappedType = mappedType;
        this.overrideBy = overrideBy;
        this.primaryKey = primaryKey;
        this.autoIncrement = autoIncrement;
        this.ordinal = ordinal;
    }

    /** @return 源列名 */
    public String getSourceColumn() {
        return sourceColumn;
    }

    /** @return 目标列名 */
    public String getTargetColumn() {
        return targetColumn;
    }

    /** @return 源类型串 */
    public String getSourceType() {
        return sourceType;
    }

    /** @return 自动映射类型 */
    public String getTargetType() {
        return targetType;
    }

    /** @return 人工覆盖类型，可能为 null */
    public String getMappedType() {
        return mappedType;
    }

    /** @return 覆盖人，可能为 null */
    public String getOverrideBy() {
        return overrideBy;
    }

    /** @return 是否主键 */
    public boolean isPrimaryKey() {
        return primaryKey;
    }

    /** @return 是否自增 */
    public boolean isAutoIncrement() {
        return autoIncrement;
    }

    /** @return 顺序 */
    public int getOrdinal() {
        return ordinal;
    }

    /**
     * 生效类型：人工覆盖优先。
     *
     * @return 生效的目标类型
     */
    public String effectiveType() {
        return mappedType == null || mappedType.isBlank() ? targetType : mappedType;
    }
}
