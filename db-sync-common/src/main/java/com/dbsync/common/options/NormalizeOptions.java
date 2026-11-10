package com.dbsync.common.options;

import com.dbsync.common.enums.NormalizeRuleType;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 归一化选项（不可变，架构 §4.4.2）。
 *
 * <p><b>强制项</b>（无开关，构造器固定 true）：{@code COLUMN_ORDER_BY_NAME}、
 * {@code NULL_SENTINEL}、{@code BINARY_AS_BASE64}、{@code TIME_TO_UTC_MILLIS}。
 *
 * <p><b>重要限制（必须写清）</b>：{@code floatTolerance} 只用于 {@code RowDiffer} 的逐列比较，
 * <b>不参与行哈希</b> —— 容差关系不可传递（a≈b、b≈c 不代表 a≈c），若参与哈希会导致
 * 哈希与逐列比较的结论不一致（K4 类故障）。因此浮点容差开启时，
 * 行哈希只能用于"是否需要下钻"的粗判，最终结论以下钻的逐列比较为准。
 */
public final class NormalizeOptions {

    /** 时间精度。 */
    public enum TimePrecision {
        /** 秒。 */
        SECONDS(0),
        /** 毫秒。 */
        MILLIS(3),
        /** 微秒（默认）。 */
        MICROS(6);

        private final int digits;

        TimePrecision(int digits) {
            this.digits = digits;
        }

        /** @return 小数位 */
        public int digits() {
            return digits;
        }
    }

    /** NULL 的哈希哨兵（绝不与空串混同）。 */
    public static final String NULL_SENTINEL = "\u0000NULL";
    /** 空串的哈希哨兵。 */
    public static final String EMPTY_SENTINEL = "\u0001EMPTY";

    private final boolean columnOrderByName;
    private final boolean nullSentinel;
    private final boolean binaryAsBase64;
    private final boolean emptyAsNull;
    private final boolean decimalTrimZeros;
    private final TimePrecision timePrecision;
    private final ZoneId sourceTimeZone;
    private final boolean trimWhitespace;
    private final boolean caseInsensitive;
    private final double floatTolerance;
    private final int lobCompareLimitBytes;

    private NormalizeOptions(boolean emptyAsNull, boolean decimalTrimZeros, TimePrecision timePrecision,
                             ZoneId sourceTimeZone, boolean trimWhitespace, boolean caseInsensitive,
                             double floatTolerance, int lobCompareLimitBytes) {
        // 强制项恒为 true，不提供任何开关（架构 §4.4.2）
        this.columnOrderByName = true;
        this.nullSentinel = true;
        this.binaryAsBase64 = true;
        this.emptyAsNull = emptyAsNull;
        this.decimalTrimZeros = decimalTrimZeros;
        this.timePrecision = timePrecision == null ? TimePrecision.MICROS : timePrecision;
        this.sourceTimeZone = sourceTimeZone == null ? ZoneId.of("Asia/Shanghai") : sourceTimeZone;
        this.trimWhitespace = trimWhitespace;
        this.caseInsensitive = caseInsensitive;
        this.floatTolerance = Math.max(0.0d, floatTolerance);
        this.lobCompareLimitBytes = lobCompareLimitBytes <= 0 ? 65536 : lobCompareLimitBytes;
    }

    /**
     * @return 默认选项（架构 §4.4.2 默认列）
     */
    public static NormalizeOptions defaults() {
        return new NormalizeOptions(true, true, TimePrecision.MICROS, ZoneId.of("Asia/Shanghai"),
                false, false, 0.0d, 65536);
    }

    /** @return 列顺序按列名排序（恒 true） */
    public boolean isColumnOrderByName() {
        return columnOrderByName;
    }

    /** @return NULL 独立编码（恒 true） */
    public boolean isNullSentinel() {
        return nullSentinel;
    }

    /** @return 二进制 Base64（恒 true） */
    public boolean isBinaryAsBase64() {
        return binaryAsBase64;
    }

    /** @return 空串视同 NULL */
    public boolean isEmptyAsNull() {
        return emptyAsNull;
    }

    /** @return DECIMAL 去尾零 */
    public boolean isDecimalTrimZeros() {
        return decimalTrimZeros;
    }

    /** @return 时间精度 */
    public TimePrecision getTimePrecision() {
        return timePrecision;
    }

    /** @return 源时区 */
    public ZoneId getSourceTimeZone() {
        return sourceTimeZone;
    }

    /** @return 是否裁剪首尾空格 */
    public boolean isTrimWhitespace() {
        return trimWhitespace;
    }

    /** @return 是否大小写不敏感 */
    public boolean isCaseInsensitive() {
        return caseInsensitive;
    }

    /** @return 浮点容差（仅逐列比较使用） */
    public double getFloatTolerance() {
        return floatTolerance;
    }

    /** @return LOB 比较上限（字节） */
    public int getLobCompareLimitBytes() {
        return lobCompareLimitBytes;
    }

    /**
     * 派生：空串策略。
     *
     * @param v true = EMPTY_AS_NULL
     * @return 新实例
     */
    public NormalizeOptions withEmptyAsNull(boolean v) {
        return new NormalizeOptions(v, decimalTrimZeros, timePrecision, sourceTimeZone,
                trimWhitespace, caseInsensitive, floatTolerance, lobCompareLimitBytes);
    }

    /**
     * 派生：DECIMAL 去尾零。
     *
     * @param v 开关
     * @return 新实例
     */
    public NormalizeOptions withDecimalTrimZeros(boolean v) {
        return new NormalizeOptions(emptyAsNull, v, timePrecision, sourceTimeZone,
                trimWhitespace, caseInsensitive, floatTolerance, lobCompareLimitBytes);
    }

    /**
     * 派生：时间精度。
     *
     * @param v 精度
     * @return 新实例
     */
    public NormalizeOptions withTimePrecision(TimePrecision v) {
        return new NormalizeOptions(emptyAsNull, decimalTrimZeros, v, sourceTimeZone,
                trimWhitespace, caseInsensitive, floatTolerance, lobCompareLimitBytes);
    }

    /**
     * 派生：源时区。
     *
     * @param v 时区
     * @return 新实例
     */
    public NormalizeOptions withSourceTimeZone(ZoneId v) {
        return new NormalizeOptions(emptyAsNull, decimalTrimZeros, timePrecision, v,
                trimWhitespace, caseInsensitive, floatTolerance, lobCompareLimitBytes);
    }

    /**
     * 派生：裁剪空格。
     *
     * @param v 开关
     * @return 新实例
     */
    public NormalizeOptions withTrimWhitespace(boolean v) {
        return new NormalizeOptions(emptyAsNull, decimalTrimZeros, timePrecision, sourceTimeZone,
                v, caseInsensitive, floatTolerance, lobCompareLimitBytes);
    }

    /**
     * 派生：大小写不敏感。
     *
     * @param v 开关
     * @return 新实例
     */
    public NormalizeOptions withCaseInsensitive(boolean v) {
        return new NormalizeOptions(emptyAsNull, decimalTrimZeros, timePrecision, sourceTimeZone,
                trimWhitespace, v, floatTolerance, lobCompareLimitBytes);
    }

    /**
     * 派生：浮点容差。
     *
     * @param v eps
     * @return 新实例
     */
    public NormalizeOptions withFloatTolerance(double v) {
        return new NormalizeOptions(emptyAsNull, decimalTrimZeros, timePrecision, sourceTimeZone,
                trimWhitespace, caseInsensitive, v, lobCompareLimitBytes);
    }

    /**
     * 派生：LOB 上限。
     *
     * @param v 字节
     * @return 新实例
     */
    public NormalizeOptions withLobCompareLimitBytes(int v) {
        return new NormalizeOptions(emptyAsNull, decimalTrimZeros, timePrecision, sourceTimeZone,
                trimWhitespace, caseInsensitive, floatTolerance, v);
    }

    /**
     * 报告展示视图（架构 §4.4.3 的"忽略/归一化规则（本次比对实际生效）"区块）。
     *
     * <p>键为规则名，值为"是否生效 + 参数"。**必须包含全部 11 条规则**，
     * 否则报告无法证明"没有静默忽略"（CMP-05）。
     *
     * @return 有序不可变 map
     */
    public Map<String, String> toReportMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(NormalizeRuleType.COLUMN_ORDER_BY_NAME.name(), "已启用（强制）：列顺序按列名排序");
        map.put(NormalizeRuleType.NULL_SENTINEL.name(), "已启用（强制）：NULL 独立编码 " + NULL_SENTINEL);
        map.put(NormalizeRuleType.EMPTY_AS_NULL.name(), emptyAsNull
                ? "已启用：空串视同 NULL（EMPTY_AS_NULL）" : "已关闭：空串与 NULL 严格区分（STRICT）");
        map.put(NormalizeRuleType.DECIMAL_TRIM_ZEROS.name(),
                decimalTrimZeros ? "已启用：DECIMAL 去尾零" : "已关闭：DECIMAL 保留原始标度");
        map.put(NormalizeRuleType.TIME_TO_UTC_MILLIS.name(),
                "已启用（强制）：时间统一 UTC，" + sourceTimeZone.getId() + " → UTC");
        map.put(NormalizeRuleType.TIME_PRECISION.name(),
                "已启用：时间精度 " + timePrecision.name() + "（" + timePrecision.digits() + " 位小数）");
        map.put(NormalizeRuleType.TRIM_WHITESPACE.name(),
                trimWhitespace ? "已启用：首尾空格裁剪" : "已关闭：保留首尾空格");
        map.put(NormalizeRuleType.CASE_INSENSITIVE.name(),
                caseInsensitive ? "已启用：大小写不敏感" : "已关闭：大小写敏感");
        map.put(NormalizeRuleType.FLOAT_TOLERANCE.name(),
                "eps=" + floatTolerance + (floatTolerance == 0.0d ? "（精确比较）" : "（仅逐列比较生效）"));
        map.put(NormalizeRuleType.BINARY_AS_BASE64.name(), "已启用（强制）：二进制 Base64");
        map.put(NormalizeRuleType.LOB_TRUNCATE.name(),
                "上限 " + lobCompareLimitBytes + " bytes（超限记 WARN）");
        return java.util.Collections.unmodifiableMap(map);
    }

    @Override
    public String toString() {
        return "NormalizeOptions[emptyAsNull=" + emptyAsNull + ",trimZeros=" + decimalTrimZeros
                + ",precision=" + timePrecision + ",tz=" + sourceTimeZone.getId()
                + ",trim=" + trimWhitespace + ",ci=" + caseInsensitive + ",eps=" + floatTolerance + "]";
    }
}
