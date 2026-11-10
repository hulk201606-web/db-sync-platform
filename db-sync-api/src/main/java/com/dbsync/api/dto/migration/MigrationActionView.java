package com.dbsync.api.dto.migration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 迁移动作结果（start/pause/resume/cancel）。
 *
 * @param taskId    任务 ID
 * @param action    动作
 * @param taskState 动作后的任务状态
 * @param applied   是否真的执行了（false 表示幂等命中：重复调用无副作用）
 */
public record MigrationActionView(long taskId, String action, String taskState, boolean applied) {

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", taskId);
        map.put("action", action);
        map.put("taskState", taskState);
        map.put("applied", applied);
        return map;
    }
}
