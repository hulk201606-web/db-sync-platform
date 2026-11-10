package com.dbsync.common.options;

import com.dbsync.common.enums.IdentifierCase;

/**
 * 结构映射选项（DDL 生成用，不可变）。
 */
public final class MappingOptions {

    private final TypeMappingOptions typeMapping;
    private final IdentifierCase identifierCase;
    private final boolean includeComments;
    private final boolean includeIndexes;
    private final boolean includeUniqueKeys;
    private final boolean dropTableIfExists;
    private final String targetSchema;

    /**
     * 构造。
     *
     * @param typeMapping     类型映射选项
     * @param identifierCase  标识符大小写
     * @param includeComments 是否输出注释
     * @param includeIndexes  是否输出索引
     * @param includeUniqueKeys 是否输出唯一键
     * @param dropTableIfExists 是否先 DROP
     * @param targetSchema    目标 schema（可 null）
     */
    public MappingOptions(TypeMappingOptions typeMapping, IdentifierCase identifierCase,
                          boolean includeComments, boolean includeIndexes, boolean includeUniqueKeys,
                          boolean dropTableIfExists, String targetSchema) {
        this.typeMapping = typeMapping == null ? TypeMappingOptions.defaults() : typeMapping;
        this.identifierCase = identifierCase == null ? IdentifierCase.PRESERVE : identifierCase;
        this.includeComments = includeComments;
        this.includeIndexes = includeIndexes;
        this.includeUniqueKeys = includeUniqueKeys;
        this.dropTableIfExists = dropTableIfExists;
        this.targetSchema = targetSchema;
    }

    /**
     * @return 默认选项（含注释/索引/唯一键，不 DROP）
     */
    public static MappingOptions defaults() {
        return new MappingOptions(TypeMappingOptions.defaults(), IdentifierCase.PRESERVE,
                true, true, true, false, null);
    }

    /** @return 类型映射选项 */
    public TypeMappingOptions getTypeMapping() {
        return typeMapping;
    }

    /** @return 标识符大小写 */
    public IdentifierCase getIdentifierCase() {
        return identifierCase;
    }

    /** @return 是否输出注释 */
    public boolean isIncludeComments() {
        return includeComments;
    }

    /** @return 是否输出索引 */
    public boolean isIncludeIndexes() {
        return includeIndexes;
    }

    /** @return 是否输出唯一键 */
    public boolean isIncludeUniqueKeys() {
        return includeUniqueKeys;
    }

    /** @return 是否先 DROP */
    public boolean isDropTableIfExists() {
        return dropTableIfExists;
    }

    /** @return 目标 schema */
    public String getTargetSchema() {
        return targetSchema;
    }
}
