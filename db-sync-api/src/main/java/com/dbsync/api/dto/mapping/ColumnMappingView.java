package com.dbsync.api.dto.mapping;

import com.dbsync.common.model.ColumnMapping;

/**
 * 列映射视图。
 *
 * @param sourceColumn  源列
 * @param targetColumn  目标列
 * @param sourceType    源类型
 * @param targetType    自动映射类型
 * @param mappedType    人工覆盖类型（null 表示未覆盖）
 * @param overrideBy    覆盖人（null 表示未覆盖，T43 验收 4 断言此字段）
 * @param effectiveType 最终生效类型
 * @param primaryKey    是否主键
 * @param autoIncrement 是否自增
 * @param ordinal       顺序
 */
public record ColumnMappingView(String sourceColumn, String targetColumn, String sourceType,
                                String targetType, String mappedType, String overrideBy,
                                String effectiveType, boolean primaryKey, boolean autoIncrement,
                                int ordinal) {

    /**
     * 从模型转换。
     *
     * @param c 列映射
     * @return 视图
     */
    public static ColumnMappingView of(ColumnMapping c) {
        return new ColumnMappingView(c.getSourceColumn(), c.getTargetColumn(), c.getSourceType(),
                c.getTargetType(), c.getMappedType(), c.getOverrideBy(), c.effectiveType(),
                c.isPrimaryKey(), c.isAutoIncrement(), c.getOrdinal());
    }
}
