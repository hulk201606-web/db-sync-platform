package com.dbsync.common.enums;

/**
 * 审计结果。越权（DENIED）也必须落审计（PRD SEC-01）。
 */
public enum AuditResult {
    /** 成功。 */
    SUCCESS,
    /** 越权拒绝。 */
    DENIED,
    /** 执行失败。 */
    FAILED
}
