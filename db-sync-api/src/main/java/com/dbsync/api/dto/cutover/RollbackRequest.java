package com.dbsync.api.dto.cutover;

/**
 * 回滚请求。
 *
 * @param reason      回滚原因（必填且 ≥5 字，落审计与计划）
 * @param confirmText 确认文本（必须为 {@value #CONFIRM_TEXT}，防误点）
 */
public record RollbackRequest(String reason, String confirmText) {

    /** 确认文本常量。 */
    public static final String CONFIRM_TEXT = "ROLLBACK";
}
