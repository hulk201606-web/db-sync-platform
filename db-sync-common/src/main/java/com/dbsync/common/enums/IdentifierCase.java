package com.dbsync.common.enums;

/**
 * 标识符大小写策略。归一化必须使用 {@link java.util.Locale#ROOT}，避免土耳其 "i" 问题。
 */
public enum IdentifierCase {
    /** 原样保留（调用方负责加引号）。 */
    PRESERVE,
    /** 转大写。 */
    UPPER,
    /** 转小写。 */
    LOWER
}
