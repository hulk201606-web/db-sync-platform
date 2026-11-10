package com.dbsync.api.dto.compare;

import java.util.List;

/**
 * 修复请求（T37：replay / fix-source 共用）。
 *
 * @param diffIds  差异 ID 列表（单条也走批量）
 * @param reason   理由（{@code fix-source} 必填且 ≥10 字）
 * @param operator 复核人（{@code fix-source} 必填，双人复核的第二个名字）
 */
public record RepairRequest(List<Long> diffIds, String reason, String operator) {
}
