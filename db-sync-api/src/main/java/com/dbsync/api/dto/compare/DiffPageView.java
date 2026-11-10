package com.dbsync.api.dto.compare;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 差异分页视图。
 *
 * @param jobId  作业 ID
 * @param page   页码（1 起）
 * @param size   每页条数
 * @param total  总条数（按 type 过滤后）
 * @param type   过滤类型（可 null）
 * @param items  差异明细
 */
public record DiffPageView(long jobId, int page, int size, long total, String type,
                           List<Map<String, Object>> items) {

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jobId", jobId);
        map.put("page", page);
        map.put("size", size);
        map.put("total", total);
        map.put("type", type);
        map.put("items", items);
        return map;
    }
}
