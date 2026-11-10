package com.dbsync.api.dto.cdc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 触发器部署结果（T32）。
 *
 * @param taskId         任务 ID
 * @param installedCount 成功部署的表数
 * @param tables         明细（表名 → 触发器名）
 */
public record TriggerInstallView(long taskId, int installedCount, List<String> tables) {

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", taskId);
        map.put("installedCount", installedCount);
        map.put("tables", tables);
        return map;
    }
}
