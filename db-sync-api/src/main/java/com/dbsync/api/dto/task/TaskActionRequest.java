package com.dbsync.api.dto.task;

/**
 * 任务状态动作请求。
 *
 * @param action 动作：READY / ARCHIVE（其余动作走迁移/增量专用端点）
 * @param reason 理由（归档必填，落审计）
 */
public record TaskActionRequest(String action, String reason) {
}
