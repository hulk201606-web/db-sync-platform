package com.dbsync.api.dto.cdc;

/**
 * 切换增量模式请求（T32）。
 *
 * @param mode   目标模式（SDK_WRITE / TRIGGER_LOG / TIMESTAMP_VERSION）
 * @param reason 切换理由（落审计）
 */
public record CdcModeRequest(String mode, String reason) {
}
