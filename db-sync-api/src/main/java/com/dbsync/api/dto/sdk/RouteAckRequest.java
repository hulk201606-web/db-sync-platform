package com.dbsync.api.dto.sdk;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * SDK 路由确认请求（PRD CUT-01：切流侧据此判断"业务侧是否已生效"）。
 *
 * @param taskId       任务 ID
 * @param instanceId   业务实例标识（多实例部署时用于区分是谁 ack 的，只落日志与审计上下文）
 * @param ackedVersion 已生效的路由版本（必须 ≥0；平台只接受单调递增）
 */
public record RouteAckRequest(
        @NotNull(message = "taskId 不能为空")
        Long taskId,
        String instanceId,
        @NotNull(message = "ackedVersion 不能为空")
        @Min(value = 0, message = "ackedVersion 不能为负")
        Long ackedVersion) {
}
