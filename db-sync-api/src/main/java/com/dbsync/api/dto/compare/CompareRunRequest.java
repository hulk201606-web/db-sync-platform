package com.dbsync.api.dto.compare;

/**
 * 发起比对请求（T37）。
 *
 * @param tableMappingId 只比对某张表（可空 → 全部启用表）
 * @param blockRows      每块行数（默认 100000）
 * @param parallelism    并发块数（默认 8）
 * @param passThreshold  通过阈值（默认取 Gate 阈值 0.9999）
 * @param drillDown      是否对差异块下钻行级比对（默认 true）
 * @param normalizeJson  归一化选项（JSON 字符串，可空 → 默认规则）
 */
public record CompareRunRequest(Long tableMappingId, Integer blockRows, Integer parallelism,
                                Double passThreshold, Boolean drillDown, String normalizeJson) {
}
