package com.dbsync.api.dto.mapping;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DDL 预览（{@code GET /api/tasks/{id}/mappings/{mid}/ddl}）。
 *
 * @param tableMappingId 表映射 ID
 * @param sourceTable    源表
 * @param targetTable    目标表
 * @param createTable    CREATE TABLE 语句
 * @param indexes        索引语句
 * @param comments       注释语句
 * @param riskTags       风险标签（如 {@code R9-NUMBER-38-10}）
 */
public record DdlView(long tableMappingId, String sourceTable, String targetTable,
                      String createTable, java.util.List<String> indexes,
                      java.util.List<String> comments, java.util.List<String> riskTags) {

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tableMappingId", tableMappingId);
        map.put("sourceTable", sourceTable);
        map.put("targetTable", targetTable);
        map.put("createTable", createTable);
        map.put("indexes", indexes);
        map.put("comments", comments);
        map.put("riskTags", riskTags);
        return map;
    }
}
