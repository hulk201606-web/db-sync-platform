package com.dbsync.api.dto.sdk;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SDK 路由确认结果。
 *
 * <p>{@code applied=false} 有两种正常原因（都不是错误，SDK 无需重试）：
 * <ol>
 *   <li>该任务还没有切流计划（{@code note=NO_PLAN}）；</li>
 *   <li>ack 的版本不比库里的新（{@code note=STALE_ACK}）——乱序到达的旧 ack 必须被忽略，
 *       否则会把"已生效版本"拉回去，切流侧就会误以为业务侧退版了。</li>
 * </ol>
 *
 * @param taskId          任务 ID
 * @param planId          计划 ID（无计划时为 0）
 * @param instanceId      业务实例标识
 * @param ackedVersion    本次 ack 的版本
 * @param previousVersion 落库前的版本（无计划时为 0）
 * @param applied         是否被采纳
 * @param note            说明（APPLIED / STALE_ACK / NO_PLAN）
 */
public record RouteAckView(long taskId, long planId, String instanceId, long ackedVersion,
                           long previousVersion, boolean applied, String note) {

    /**
     * 转成响应体 {@code data}。
     *
     * @return 有序 map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", Long.valueOf(taskId));
        map.put("planId", Long.valueOf(planId));
        map.put("instanceId", instanceId);
        map.put("ackedVersion", Long.valueOf(ackedVersion));
        map.put("previousVersion", Long.valueOf(previousVersion));
        map.put("applied", Boolean.valueOf(applied));
        map.put("note", note);
        return map;
    }
}
