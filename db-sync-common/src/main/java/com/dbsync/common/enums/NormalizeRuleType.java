package com.dbsync.common.enums;

/**
 * 归一化规则类型（架构 §4.4.2，按优先级 P0→P10）。
 *
 * <p>P0/P1/P4/P9 为强制项（不可关闭）；其余可配，但**只要开关变化就必须落
 * {@code sync_compare_job.normalize_json} 快照**（CMP-05 显式性要求）。
 */
public enum NormalizeRuleType {

    /** P0 列顺序按列名排序（强制）。 */
    COLUMN_ORDER_BY_NAME("P0", "列顺序按列名排序", true),
    /** P1 NULL 独立编码（强制）。 */
    NULL_SENTINEL("P1", "NULL 独立编码", true),
    /** P2 空串视同 NULL。 */
    EMPTY_AS_NULL("P2", "空串视同 NULL", false),
    /** P3 DECIMAL 去尾零。 */
    DECIMAL_TRIM_ZEROS("P3", "DECIMAL 去尾零", false),
    /** P4 时间统一转 UTC（强制）。 */
    TIME_TO_UTC_MILLIS("P4", "时间统一 UTC", true),
    /** P5 时间精度对齐到微秒。 */
    TIME_PRECISION("P5", "时间精度对齐", false),
    /** P6 首尾空格裁剪。 */
    TRIM_WHITESPACE("P6", "首尾空格裁剪", false),
    /** P7 大小写不敏感。 */
    CASE_INSENSITIVE("P7", "大小写不敏感", false),
    /** P8 浮点容差。 */
    FLOAT_TOLERANCE("P8", "浮点容差", false),
    /** P9 二进制转 Base64（强制）。 */
    BINARY_AS_BASE64("P9", "二进制 Base64", true),
    /** P10 大字段比较上限。 */
    LOB_TRUNCATE("P10", "大字段比较上限", false);

    private final String priority;
    private final String label;
    private final boolean mandatory;

    NormalizeRuleType(String priority, String label, boolean mandatory) {
        this.priority = priority;
        this.label = label;
        this.mandatory = mandatory;
    }

    /**
     * @return 优先级标签（P0..P10）
     */
    public String priority() {
        return priority;
    }

    /**
     * @return 报告展示用的中文标签
     */
    public String label() {
        return label;
    }

    /**
     * @return true 表示强制项，无开关
     */
    public boolean isMandatory() {
        return mandatory;
    }
}
