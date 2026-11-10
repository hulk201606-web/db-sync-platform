package com.dbsync.api.dto.migration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按表进度列表（MIG-06 表级）。
 *
 * <p>{@code items} 的顺序与"映射列表"一致（按 mapping id 升序），
 * 便于前端把表级进度与映射表逐行对齐；没有分片的表也会出现（各项为 0），
 * 否则前端无法区分"表没分片"和"表不存在"。
 *
 * @param taskId 任务 ID
 * @param items  各表进度
 */
public record TableProgressListView(long taskId, List<TableProgressView> items) {

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        List<Object> data = new ArrayList<>(items.size());
        for (TableProgressView item : items) {
            data.add(item.toMap());
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", taskId);
        map.put("items", data);
        return map;
    }
}
