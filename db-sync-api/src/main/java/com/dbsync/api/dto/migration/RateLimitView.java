package com.dbsync.api.dto.migration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 限速调整结果（MIG-05）。
 *
 * @param taskId     任务 ID
 * @param applied    是否<b>已热生效</b>（false 表示只落了库，下次启动才生效）
 * @param readLimit  应用后的读限速（行/秒，0 表示不限速）
 * @param writeLimit 应用后的写限速（行/秒，0 表示不限速）
 * @param effective  生效方式：{@code IMMEDIATE}（本实例正在跑该任务，已热更新）/
 *                   {@code NEXT_START}（仅落库）
 */
public record RateLimitView(long taskId, boolean applied, long readLimit, long writeLimit,
                            String effective) {

    /** 立即生效（本实例持有该任务的限速器并已 updateLimits）。 */
    public static final String IMMEDIATE = "IMMEDIATE";

    /** 下次启动生效（任务未在本实例运行，只更新了 sync_task.rate_limit_rows）。 */
    public static final String NEXT_START = "NEXT_START";

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", taskId);
        map.put("applied", applied);
        map.put("readLimit", readLimit);
        map.put("writeLimit", writeLimit);
        map.put("effective", effective);
        return map;
    }
}
