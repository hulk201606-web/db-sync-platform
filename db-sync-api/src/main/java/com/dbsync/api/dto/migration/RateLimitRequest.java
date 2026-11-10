package com.dbsync.api.dto.migration;

/**
 * 运行中调整限速请求（MIG-05）。
 *
 * <p>两种写法（<b>二选一</b>，同时给时以分项为准）：
 * <ul>
 *   <li>{@code {"rowsPerSec": 10000}} —— 读侧与写侧同值（最常见）；</li>
 *   <li>{@code {"readRowsPerSec": 10000, "writeRowsPerSec": 5000}} —— 分别控制，
 *       读快写慢是迁移的典型诉求（目标库还要服务线上读）。</li>
 * </ul>
 *
 * <p>{@code 0} 表示不限速。分项只给一侧时，另一侧<b>沿用已给的值</b>
 * （而不是归 0）：避免漏传一个字段就把另一侧悄悄放开成不限速。
 *
 * <p>用包装类型 {@code Long} 而不是 {@code long}：必须区分"没传"与"传了 0"，
 * 前者是"未指定"，后者是"不限速"，二者语义完全相反。
 *
 * @param rowsPerSec     整体限速（行/秒，0 不限速，可 null）
 * @param readRowsPerSec 读限速（行/秒，0 不限速，可 null）
 * @param writeRowsPerSec 写限速（行/秒，0 不限速，可 null）
 */
public record RateLimitRequest(Long rowsPerSec, Long readRowsPerSec, Long writeRowsPerSec) {
}
