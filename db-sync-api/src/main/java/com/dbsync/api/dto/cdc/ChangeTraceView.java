package com.dbsync.api.dto.cdc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按主键追溯结果（T32：最近 20 条变更）。
 *
 * @param taskId 任务 ID
 * @param table  表名
 * @param pk     主键文本
 * @param count  条数
 * @param items  变更条目（seq 倒序）
 */
public record ChangeTraceView(long taskId, String table, String pk, int count,
                              List<Map<String, Object>> items) {

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", taskId);
        map.put("table", table);
        map.put("pk", pk);
        map.put("count", count);
        map.put("items", items);
        return map;
    }
}
