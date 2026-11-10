package com.dbsync.api.dto.migration;

import java.util.List;

/**
 * 死信重放/丢弃请求。
 *
 * @param ids    死信 ID 列表（单条也走批量）
 * @param reason 理由（丢弃必填，重放建议填）
 */
public record ReplayRequest(List<Long> ids, String reason) {
}
