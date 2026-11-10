package com.dbsync.api.dto.cdc;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 增量健康视图（T32：延迟 P95 / 积压 / 失败率 / 最近应用时间）。
 *
 * @param taskId        任务 ID
 * @param mode          当前增量模式
 * @param latencyP95Ms  延迟 P95（毫秒）
 * @param backlog       积压条数（status=NEW）
 * @param failRate      最近 15 分钟失败率（0.0~1.0）
 * @param lastApplyAt   最近成功应用时间（ISO-8601，可 null）
 */
public record CdcLatencyView(long taskId, String mode, long latencyP95Ms, long backlog,
                             double failRate, String lastApplyAt) {

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", taskId);
        map.put("mode", mode);
        map.put("latencyP95Ms", latencyP95Ms);
        map.put("backlog", backlog);
        map.put("failRate", failRate);
        map.put("lastApplyAt", lastApplyAt);
        return map;
    }
}
