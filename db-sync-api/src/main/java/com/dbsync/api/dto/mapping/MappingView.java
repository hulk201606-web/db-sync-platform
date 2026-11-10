package com.dbsync.api.dto.mapping;

import com.dbsync.common.model.TableMapping;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 表映射视图。
 *
 * @param id               映射 ID
 * @param taskId           任务 ID
 * @param sourceSchema     源 schema
 * @param sourceTable      源表
 * @param targetSchema     目标 schema
 * @param targetTable      目标表
 * @param hasPrimaryKey    是否有主键
 * @param rangeKind        区间类型（NUMERIC/TEXT/NONE）
 * @param rowCountEstimate 估算行数
 * @param ddlApplied       目标表是否已建
 * @param enabled          是否参与同步
 * @param conflictPolicy   表级冲突策略（{@code sync_table_mapping.conflict_policy}，CDC-06）
 * @param generatedDdl     DDL（列表页可为 null，详情页给全量）
 */
public record MappingView(long id, long taskId, String sourceSchema, String sourceTable,
                          String targetSchema, String targetTable, boolean hasPrimaryKey,
                          String rangeKind, long rowCountEstimate, boolean ddlApplied,
                          boolean enabled, String conflictPolicy, String generatedDdl) {

    /**
     * 从模型转换（不含 DDL）。
     *
     * @param m 映射
     * @return 视图
     */
    public static MappingView of(TableMapping m) {
        return of(m, false);
    }

    /**
     * 从模型转换。
     *
     * @param m          映射
     * @param withDdl    是否带 DDL 文本
     * @return 视图
     */
    public static MappingView of(TableMapping m, boolean withDdl) {
        return new MappingView(m.getId(), m.getTaskId(), m.getSourceSchema(), m.getSourceTable(),
                m.getTargetSchema(), m.getTargetTable(), m.isHasPrimaryKey(), m.getRangeKind(),
                m.getRowCountEstimate(), m.isDdlApplied(), m.isEnabled(),
                m.getConflictPolicy() == null ? null : m.getConflictPolicy().name(),
                withDdl ? m.getGeneratedDdl() : null);
    }

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("taskId", taskId);
        map.put("sourceSchema", sourceSchema);
        map.put("sourceTable", sourceTable);
        map.put("targetSchema", targetSchema);
        map.put("targetTable", targetTable);
        map.put("hasPrimaryKey", hasPrimaryKey);
        map.put("rangeKind", rangeKind);
        map.put("rowCountEstimate", rowCountEstimate);
        map.put("ddlApplied", ddlApplied);
        map.put("enabled", enabled);
        map.put("conflictPolicy", conflictPolicy);
        map.put("generatedDdl", generatedDdl);
        return map;
    }
}
