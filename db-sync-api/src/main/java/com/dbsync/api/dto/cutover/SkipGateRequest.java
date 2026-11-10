package com.dbsync.api.dto.cutover;

/**
 * 跳过 Gate 请求（架构 §4.6.3 "三件套"）。
 *
 * <p>三个条件缺一不可：
 * <ol>
 *   <li>{@code reason} 长度 ≥10（说明为什么必须跳过）；</li>
 *   <li>{@code confirmText} 必须精确等于 {@value #CONFIRM_TEXT}（防误点）；</li>
 *   <li>环境为 PROD 时必须提供 {@code secondConfirmUser}（双人复核，SEC-06）。</li>
 * </ol>
 *
 * @param reason            理由（≥10 字）
 * @param confirmText       确认文本（必须为 {@value #CONFIRM_TEXT}）
 * @param secondConfirmUser 第二复核人（PROD 必填）
 */
public record SkipGateRequest(String reason, String confirmText, String secondConfirmUser) {

    /** 确认文本常量。 */
    public static final String CONFIRM_TEXT = "SKIP-GATE";
}
