package com.dbsync.api.dto.migration;

import com.dbsync.migration.RowCountDiff;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 核数结果视图（PRD ORC-05）。
 *
 * @param taskId   任务 ID
 * @param allMatch 全部表行数一致
 * @param diffs    差异清单（一致时为空）
 */
public record RowCountVerifyView(long taskId, boolean allMatch,
                                 List<Map<String, Object>> diffs) {

    /**
     * 从差异列表转换。
     *
     * @param taskId 任务 ID
     * @param diffs  差异
     * @return 视图
     */
    public static RowCountVerifyView of(long taskId, List<RowCountDiff> diffs) {
        List<Map<String, Object>> rows = new ArrayList<>(diffs.size());
        boolean allMatch = true;
        for (RowCountDiff diff : diffs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tableMappingId", diff.getTableMappingId());
            row.put("sourceTable", diff.getSourceTable());
            row.put("targetTable", diff.getTargetTable());
            row.put("sourceRows", diff.getSourceRows());
            row.put("targetRows", diff.getTargetRows());
            row.put("diff", diff.diff());
            row.put("match", diff.isMatch());
            rows.add(row);
            if (!diff.isMatch()) {
                allMatch = false;
            }
        }
        return new RowCountVerifyView(taskId, allMatch, rows);
    }

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", taskId);
        map.put("allMatch", allMatch);
        map.put("diffs", diffs);
        return map;
    }
}
