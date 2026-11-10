package com.dbsync.api.dto.datasource;

import com.dbsync.common.model.TableMeta;

/**
 * 表清单项 / 单表元数据概览。
 *
 * @param schema        schema
 * @param table         表名
 * @param columnCount   列数
 * @param hasPrimaryKey 是否有主键
 * @param estimatedRows 估算行数（-1 表示未知）
 * @param comment       注释
 */
public record TableInfoView(String schema, String table, int columnCount, boolean hasPrimaryKey,
                            long estimatedRows, String comment) {

    /**
     * 从表元数据转换。
     *
     * @param meta 表元数据
     * @return 视图
     */
    public static TableInfoView of(TableMeta meta) {
        return new TableInfoView(meta.getSchema(), meta.getTableName(), meta.getColumns().size(),
                meta.hasPrimaryKey(), meta.getEstimatedRows(), meta.getComment());
    }

    /**
     * 无元数据时的占位（仅表名）。
     *
     * @param schema schema
     * @param table  表名
     * @return 视图
     */
    public static TableInfoView brief(String schema, String table) {
        return new TableInfoView(schema, table, 0, false, -1L, null);
    }
}
