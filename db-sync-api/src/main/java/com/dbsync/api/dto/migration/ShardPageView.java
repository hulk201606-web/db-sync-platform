package com.dbsync.api.dto.migration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分片分页结果（MIG-06）。
 *
 * <p>{@code total} 是<b>过滤后</b>的总条数（前端据此算总页数），
 * {@code page} 从 1 开始，{@code size} 是归一化后的实际页大小
 * （传入非法值或超上限时返回的 size 与真实查询一致，前端可据此纠正）。
 *
 * @param total 过滤后总条数
 * @param page  当前页（1 起）
 * @param size  实际页大小
 * @param items 当前页数据
 */
public record ShardPageView(long total, int page, int size, List<ShardView> items) {

    /**
     * @return 有序 Map（items 逐项转 Map，保持字段顺序稳定）
     */
    public Map<String, Object> toMap() {
        List<Object> data = new ArrayList<>(items.size());
        for (ShardView item : items) {
            data.add(item.toMap());
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("total", total);
        map.put("page", page);
        map.put("size", size);
        map.put("items", data);
        return map;
    }
}
