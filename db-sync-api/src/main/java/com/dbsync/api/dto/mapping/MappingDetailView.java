package com.dbsync.api.dto.mapping;

import java.util.List;

/**
 * 表映射详情（映射 + 列 + DDL）。
 *
 * @param mapping 表映射
 * @param columns 列映射
 */
public record MappingDetailView(MappingView mapping, List<ColumnMappingView> columns) {
}
