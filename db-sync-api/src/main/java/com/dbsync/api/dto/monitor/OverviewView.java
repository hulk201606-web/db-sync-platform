package com.dbsync.api.dto.monitor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大盘总览（T45 验收 3：任务数 / 分片热力 / 行数 / TPS / 死信数）。
 *
 * @param taskCounts      各状态任务数
 * @param totalTasks      任务总数
 * @param shardCounts     各状态分片数（全局）
 * @param shardHeat       按任务的分片热力（任务 ID → 状态计数）
 * @param committedRows   累计已提交行数
 * @param tps             各任务 TPS
 * @param totalTps        全局 TPS
 * @param deadLetterOpen  未处理死信数
 * @param aliveInstances  存活实例数
 * @param cutoverPlans    切流计划数（按状态）
 * @param recentAlerts    最近告警
 */
public record OverviewView(Map<String, Integer> taskCounts, int totalTasks,
                           Map<String, Integer> shardCounts,
                           Map<String, Map<String, Integer>> shardHeat, long committedRows,
                           Map<String, Double> tps, double totalTps, long deadLetterOpen,
                           long aliveInstances, Map<String, Integer> cutoverPlans,
                           List<Map<String, Object>> recentAlerts) {

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskCounts", taskCounts);
        map.put("totalTasks", totalTasks);
        map.put("shardCounts", shardCounts);
        map.put("shardHeat", shardHeat);
        map.put("committedRows", committedRows);
        map.put("tps", tps);
        map.put("totalTps", totalTps);
        map.put("deadLetterOpen", deadLetterOpen);
        map.put("aliveInstances", aliveInstances);
        map.put("cutoverPlans", cutoverPlans);
        map.put("recentAlerts", recentAlerts);
        return map;
    }
}
