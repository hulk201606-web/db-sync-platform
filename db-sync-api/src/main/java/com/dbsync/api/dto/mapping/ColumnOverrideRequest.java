package com.dbsync.api.dto.mapping;

/**
 * 列类型人工覆盖请求（T43 验收 4）。
 *
 * @param targetType 目标类型 DDL 片段（如 {@code VARCHAR(128)}）；为空表示撤销覆盖
 * @param reason     覆盖理由（落库前记入审计）
 */
public record ColumnOverrideRequest(String targetType, String reason) {
}
