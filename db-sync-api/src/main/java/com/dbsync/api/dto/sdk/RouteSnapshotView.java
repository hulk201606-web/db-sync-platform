package com.dbsync.api.dto.sdk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 下发给业务侧 SDK 的路由快照（PRD CUT-01）。
 *
 * <p>字段语义（与 SDK 侧 {@code com.dbsync.cdc.sdk.SdkRouteSnapshot} 一一对应）：
 * <ul>
 *   <li>{@code routeVersion}：单调递增版本，SDK 只接受"比本地更新"的快照；</li>
 *   <li>{@code state}：切流<b>已确认</b>状态（{@code NONE} 表示该任务还没有计划）；</li>
 *   <li>{@code readFrom}：本次读走哪里（{@code SOURCE}/{@code TARGET}），
 *       灰度百分比在 {@code grayRatio} 里；</li>
 *   <li>{@code writeTo}：写走哪里（{@code SOURCE}/{@code BOTH}/{@code TARGET}）；</li>
 *   <li>{@code rules}：规则明细，同时给出机器字段（{@code ratio/tableWhitelist/hashBucket}）
 *       与人类可读的 {@code dim + expr}，便于运维在 SDK 日志里直接看懂；</li>
 *   <li>{@code snapshotTtlMs}：快照陈旧上限，SDK 按它的 1/3 轮询（默认 3 秒 → 1 秒一轮）；</li>
 *   <li>{@code generatedAt}：平台生成时间（ISO-8601）。</li>
 * </ul>
 *
 * @param taskId        任务 ID
 * @param routeVersion  路由版本
 * @param state         已确认的切流状态
 * @param readFrom      读目标 SOURCE/TARGET
 * @param writeTo       写目标 SOURCE/BOTH/TARGET
 * @param grayRatio     当前生效灰度百分比
 * @param rules         规则明细
 * @param snapshotTtlMs 快照陈旧上限（毫秒）
 * @param generatedAt   生成时间（ISO-8601）
 */
public record RouteSnapshotView(long taskId, long routeVersion, String state, String readFrom,
                                String writeTo, int grayRatio, List<Map<String, Object>> rules,
                                long snapshotTtlMs, String generatedAt) {

    /**
     * 转成响应体 {@code data}（字段顺序固定，便于前端/SDK 阅读与快照比对）。
     *
     * @return 有序 map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", Long.valueOf(taskId));
        map.put("routeVersion", Long.valueOf(routeVersion));
        map.put("state", state);
        map.put("readFrom", readFrom);
        map.put("writeTo", writeTo);
        map.put("grayRatio", Integer.valueOf(grayRatio));
        map.put("rules", rules);
        map.put("snapshotTtlMs", Long.valueOf(snapshotTtlMs));
        map.put("generatedAt", generatedAt);
        return map;
    }
}
