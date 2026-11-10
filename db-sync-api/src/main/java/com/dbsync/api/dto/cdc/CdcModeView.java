package com.dbsync.api.dto.cdc;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 增量模式视图。
 *
 * @param taskId 任务 ID
 * @param mode   当前模式（切换后）
 * @param from   切换前模式
 * @param applied 是否真的切换了（幂等命中返回 false）
 */
public record CdcModeView(long taskId, String mode, String from, boolean applied) {

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", taskId);
        map.put("mode", mode);
        map.put("from", from);
        map.put("applied", applied);
        return map;
    }
}
