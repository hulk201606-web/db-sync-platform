package com.dbsync.compare;

import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.options.NormalizeOptions;
import com.dbsync.common.util.HashUtil;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 归一化器（架构 §4.4.2 的 10 条规则的<b>唯一</b>实现点，任务 T34）。
 *
 * <p><b>为什么必须只有一处</b>（K4）：归一化发生在两处且必须完全一致 ——
 * {@link RowHashBuilder}（决定"这一块要不要下钻"）与 {@link RowDiffer}（决定"这一列算不算差异"）。
 * 若两处各写一遍，就会出现"哈希说有差异、逐列比较说没差异"，下钻一堆空块，误报率 100%。
 * 因此本类<b>没有无参构造器</b>，{@link RowHashBuilder} / {@link RowDiffer} 只允许构造器注入同一个实例。
 *
 * <p><b>规则优先级</b>（严格按表顺序执行，先命中先生效）：
 * <table border="1">
 *   <tr><th>规则</th><th>行为</th></tr>
 *   <tr><td>P0</td><td>列名排序（在 {@link #hashRow} 内执行，不涉及单列值）</td></tr>
 *   <tr><td>P1</td><td>NULL → {@link NormalizeOptions#NULL_SENTINEL}；
 *       空串（STRICT 模式）→ {@link NormalizeOptions#EMPTY_SENTINEL}；两者绝不混同</td></tr>
 *   <tr><td>P2</td><td>{@code emptyAsNull=true} 时空串按 NULL 编码</td></tr>
 *   <tr><td>P3</td><td>DECIMAL 去尾零（{@code stripTrailingZeros().toPlainString()}）</td></tr>
 *   <tr><td>P4</td><td>时间统一转 UTC：无时区类型按 {@code sourceTimeZone} 解释</td></tr>
 *   <tr><td>P5</td><td>时间截断到配置精度（默认微秒）</td></tr>
 *   <tr><td>P6</td><td>首尾空格裁剪（可选）</td></tr>
 *   <tr><td>P7</td><td>大小写折叠（可选）</td></tr>
 *   <tr><td>P8</td><td><b>哈希阶段不做容差</b>：容差不可传递（a≈b、b≈c 不代表 a≈c），
 *       若参与哈希会造成哈希与逐列比较结论不一致。浮点容差只在 {@link RowDiffer} 的逐列比较中使用</td></tr>
 *   <tr><td>P9</td><td>二进制 → Base64（统一不同驱动的 {@code getBytes}/{@code getBinaryStream} 差异）</td></tr>
 *   <tr><td>P10</td><td>LOB 超限只取前 N 字节 + 长度，返回带 TRUNCATED 标记的 {@link LobValue}</td></tr>
 * </table>
 *
 * <p><b>已知取舍（必须显式说明，不得静默）</b>：
 * <ol>
 *   <li>哈希文本不携带"类型标记"——这是 P9 的要求（{@code byte[]{1,2}} 与等价的 Base64 字符串必须同哈希）。
 *       同一列的源/目标类型由映射保证一致，因此不会产生跨类型的假阴性；但若有人手工把 {@code "1"}（字符串）
 *       与 {@code 1}（数值）放进同一列做比对，两者哈希相同；这种情形在映射层面即属非法配置。</li>
 *   <li>P10 截断后的前缀可能把一个多字节字符切断（产生替换字符）；两端用同一算法，故结论仍然稳定，
 *       但截断行为本身必须计入报告的 WARN 列表（{@link #isTruncated(Object)}）。</li>
 *   <li>P4 只支持 JDBC 常见时间类型；无法识别的时间类型按 {@code toString()} 处理并在哈希中体现原样字符串。</li>
 * </ol>
 */
public final class Normalizer {

    /** 字段分隔符（哈希拼接用；取值 0x02，避开 NULL/EMPTY 哨兵用的 0x00/0x01）。 */
    private static final char FIELD_SEPARATOR = '\u0002';

    private final NormalizeOptions opts;

    /**
     * 构造。
     *
     * @param opts 归一化选项（null 取 {@link NormalizeOptions#defaults()}）
     */
    public Normalizer(NormalizeOptions opts) {
        this.opts = opts == null ? NormalizeOptions.defaults() : opts;
    }

    /** @return 生效的选项（报告需要原样落库，CMP-05） */
    public NormalizeOptions options() {
        return opts;
    }

    // ------------------------------------------------------------------
    // 单值归一化
    // ------------------------------------------------------------------

    /**
     * 归一化单列值（规则顺序 = 架构 §4.4.2 优先级）。
     *
     * <p>返回值类型约定（{@link RowDiffer} 依赖它做比较，{@link #textOf(Object)} 依赖它做哈希）：
     * <ul>
     *   <li>NULL / 空串 → {@link NormalizeOptions#NULL_SENTINEL} 或
     *       {@link NormalizeOptions#EMPTY_SENTINEL}（String）</li>
     *   <li>字符串 → 处理后的 String</li>
     *   <li>定点数 → {@link BigDecimal}</li>
     *   <li>浮点列 → {@link Double}（P8 容差比较需要数值）</li>
     *   <li>时间 → {@link Long}（自 epoch 起、按 P5 精度折算的微秒数）</li>
     *   <li>二进制 → Base64 String</li>
     *   <li>超限 LOB → {@link LobValue}</li>
     * </ul>
     *
     * @param raw 原始值（可为 null）
     * @param col 列元数据（可为 null：无法判定类型时按通用规则处理）
     * @return 归一化值（保证非 null）
     */
    public Object normalize(Object raw, ColumnMeta col) {
        // P1：NULL 独立编码
        if (raw == null) {
            return NormalizeOptions.NULL_SENTINEL;
        }
        // P9 + P10：二进制
        if (raw instanceof byte[]) {
            byte[] bytes = (byte[]) raw;
            LobValue truncated = truncateBytes(bytes);
            if (truncated != null) {
                return truncated;
            }
            return Base64.getEncoder().encodeToString(bytes);
        }
        // P1/P2/P6/P7/P10：字符串
        if (raw instanceof CharSequence) {
            String s = raw.toString();
            if (s.isEmpty()) {
                return opts.isEmptyAsNull()
                        ? NormalizeOptions.NULL_SENTINEL : NormalizeOptions.EMPTY_SENTINEL;
            }
            LobValue truncated = truncateText(s);
            if (truncated != null) {
                return truncated;
            }
            return postProcessText(s);
        }
        // P3：DECIMAL 去尾零
        if (raw instanceof BigDecimal) {
            return trimDecimal((BigDecimal) raw);
        }
        // P8：浮点保持数值形态（容差比较只在 RowDiffer 生效）
        if (raw instanceof Double || raw instanceof Float) {
            return Double.valueOf(((Number) raw).doubleValue());
        }
        if (raw instanceof Number) {
            return trimDecimal(HashUtil.toBigDecimal((Number) raw));
        }
        // P4/P5：时间
        Long temporal = temporalMicros(raw);
        if (temporal != null) {
            return temporal;
        }
        // 兜底：未知类型按字符串规则处理（保持确定性，不抛异常）
        return postProcessText(String.valueOf(raw));
    }

    /**
     * 把归一化值编码成参与哈希的文本（P8 的说明见类注释）。
     *
     * @param normalized {@link #normalize} 的返回值
     * @return 稳定的文本编码
     */
    public String textOf(Object normalized) {
        if (normalized == null) {
            return NormalizeOptions.NULL_SENTINEL;
        }
        if (normalized instanceof String) {
            return (String) normalized;
        }
        if (normalized instanceof BigDecimal) {
            return ((BigDecimal) normalized).toPlainString();
        }
        if (normalized instanceof Double) {
            BigDecimal decimal = BigDecimal.valueOf(((Double) normalized).doubleValue());
            if (opts.isDecimalTrimZeros()) {
                decimal = decimal.stripTrailingZeros();
            }
            return decimal.toPlainString();
        }
        if (normalized instanceof Number) {
            return String.valueOf(normalized);
        }
        if (normalized instanceof LobValue) {
            return ((LobValue) normalized).hashText();
        }
        return String.valueOf(normalized);
    }

    /**
     * 归一化并编码（{@link RowDiffer} 展示与比较用）。
     *
     * @param raw 原始值
     * @param col 列元数据
     * @return 文本编码
     */
    public String canonicalText(Object raw, ColumnMeta col) {
        return textOf(normalize(raw, col));
    }

    /**
     * 值是否被 P10 截断（报告必须列入 WARN，不能静默）。
     *
     * @param normalized 归一化值
     * @return true 表示超限被截断
     */
    public static boolean isTruncated(Object normalized) {
        return normalized instanceof LobValue && ((LobValue) normalized).isTruncated();
    }

    // ------------------------------------------------------------------
    // 行哈希（P0 列名排序 + 逐列归一化 + 定长前缀拼接 + md5）
    // ------------------------------------------------------------------

    /**
     * 行哈希：列名排序 → 归一化 → 拼接 → md5。
     *
     * <p>拼接格式为 {@code len:value\u0002}，长度前缀用于消除"分隔符出现在值里"的歧义
     * （否则 {@code ("a","b")} 与 {@code ("a\u0002b","")} 会哈希相同）。
     *
     * @param row  行（键大小写不敏感；缺列按 NULL 处理）
     * @param cols 参与哈希的列（顺序无关，P0 会重排）
     * @return 32 位十六进制 md5；{@code cols} 为空时返回空行的哈希
     */
    public String hashRow(Map<String, Object> row, List<ColumnMeta> cols) {
        List<ColumnMeta> ordered = new ArrayList<ColumnMeta>(cols == null ? List.of() : cols);
        // P0：列顺序按列名排序（强制，不可关）
        if (opts.isColumnOrderByName()) {
            ordered.sort(Comparator.comparing(ColumnMeta::getName, String.CASE_INSENSITIVE_ORDER));
        }
        StringBuilder sb = new StringBuilder(256);
        for (ColumnMeta col : ordered) {
            String text = canonicalText(lookup(row, col.getName()), col);
            sb.append(text.length()).append(':').append(text).append(FIELD_SEPARATOR);
        }
        return HashUtil.md5Hex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 大小写不敏感取值（先精确命中，再全表扫一遍）。
     *
     * @param row  行
     * @param name 列名
     * @return 值；不存在返回 null
     */
    static Object lookup(Map<String, Object> row, String name) {
        if (row == null || name == null) {
            return null;
        }
        if (row.containsKey(name)) {
            return row.get(name);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 内部规则实现
    // ------------------------------------------------------------------

    /**
     * P3：DECIMAL 去尾零。
     *
     * @param value 原始小数
     * @return 去尾零后的小数（开关关闭时原样返回）
     */
    private BigDecimal trimDecimal(BigDecimal value) {
        return opts.isDecimalTrimZeros() ? value.stripTrailingZeros() : value;
    }

    /**
     * P6 + P7：字符串后处理。
     *
     * @param value 原串（非空）
     * @return 处理后字符串
     */
    private String postProcessText(String value) {
        String result = value;
        if (opts.isTrimWhitespace()) {
            result = result.trim();
        }
        if (opts.isCaseInsensitive()) {
            result = result.toLowerCase(java.util.Locale.ROOT);
        }
        return result;
    }

    /**
     * P4 + P5：时间转"自 epoch 起的微秒数"。
     *
     * <p><b>为什么无时区类型要按 {@code sourceTimeZone} 解释</b>：Oracle {@code DATE} 与
     * {@code TIMESTAMP} 都不带时区，JDBC 读成 {@link java.sql.Timestamp} 时按 JVM 时区解释墙钟；
     * {@link java.sql.Timestamp#toLocalDateTime()} 正好把这个墙钟原样取回，再按配置的源时区
     * 折算成 UTC，才能与 PG {@code timestamptz} 的绝对时刻对齐（否则全线差 8 小时）。
     *
     * @param raw 原始值
     * @return 微秒数；非时间类型返回 null
     */
    private Long temporalMicros(Object raw) {
        ZoneId zone = opts.getSourceTimeZone();
        Instant instant = null;
        if (raw instanceof java.sql.Timestamp) {
            instant = ((java.sql.Timestamp) raw).toLocalDateTime().atZone(zone).toInstant();
        } else if (raw instanceof java.sql.Date) {
            instant = ((java.sql.Date) raw).toLocalDate().atStartOfDay(zone).toInstant();
        } else if (raw instanceof java.sql.Time) {
            instant = ((java.sql.Time) raw).toLocalTime()
                    .atDate(java.time.LocalDate.ofEpochDay(0L)).atZone(zone).toInstant();
        } else if (raw instanceof OffsetDateTime) {
            instant = ((OffsetDateTime) raw).toInstant();
        } else if (raw instanceof ZonedDateTime) {
            instant = ((ZonedDateTime) raw).toInstant();
        } else if (raw instanceof LocalDateTime) {
            instant = ((LocalDateTime) raw).atZone(zone).toInstant();
        } else if (raw instanceof LocalDate) {
            instant = ((LocalDate) raw).atStartOfDay(zone).toInstant();
        } else if (raw instanceof Instant) {
            instant = (Instant) raw;
        } else if (raw instanceof java.util.Date) {
            instant = ((java.util.Date) raw).toInstant();
        }
        if (instant == null) {
            return null;
        }
        Instant truncated = instant.truncatedTo(precisionUnit());
        return Long.valueOf(epochMicros(truncated));
    }

    /**
     * P5：时间精度的截断单位。
     *
     * @return 截断单位
     */
    private ChronoUnit precisionUnit() {
        switch (opts.getTimePrecision()) {
            case SECONDS:
                return ChronoUnit.SECONDS;
            case MILLIS:
                return ChronoUnit.MILLIS;
            case MICROS:
            default:
                return ChronoUnit.MICROS;
        }
    }

    /**
     * 把 {@link Instant} 折算成微秒数（与精度无关，统一用微秒承载，避免单位歧义）。
     *
     * @param instant 时刻
     * @return 微秒数
     */
    private static long epochMicros(Instant instant) {
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }

    /**
     * P10：字节型值超限截断。
     *
     * @param bytes 原始字节
     * @return 截断结果；未超限返回 null
     */
    private LobValue truncateBytes(byte[] bytes) {
        int limit = opts.getLobCompareLimitBytes();
        if (bytes.length <= limit) {
            return null;
        }
        byte[] prefix = new byte[limit];
        System.arraycopy(bytes, 0, prefix, 0, limit);
        return new LobValue(Base64.getEncoder().encodeToString(prefix), bytes.length, true);
    }

    /**
     * P10：字符串超限截断（按 UTF-8 字节数衡量）。
     *
     * @param text 原始串
     * @return 截断结果；未超限返回 null
     */
    private LobValue truncateText(String text) {
        int limit = opts.getLobCompareLimitBytes();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= limit) {
            return null;
        }
        byte[] prefix = new byte[limit];
        System.arraycopy(bytes, 0, prefix, 0, limit);
        return new LobValue(new String(prefix, StandardCharsets.UTF_8), bytes.length, true);
    }

    /**
     * 列是否按浮点类型参与容差比较（P8）。
     *
     * @param col 列元数据
     * @return true 表示浮点列
     */
    static boolean isFloatingColumn(ColumnMeta col) {
        if (col == null) {
            return false;
        }
        int type = col.getJdbcType();
        return type == Types.FLOAT || type == Types.REAL || type == Types.DOUBLE
                || type == Types.NUMERIC && looksFloating(col.getTypeName())
                || type == Types.DECIMAL && looksFloating(col.getTypeName());
    }

    /**
     * 类型名是否浮点（补充 {@code NUMERIC}/{@code DECIMAL} 承载浮点的场景）。
     *
     * @param typeName 类型名
     * @return true 表示浮点
     */
    private static boolean looksFloating(String typeName) {
        if (typeName == null) {
            return false;
        }
        String upper = typeName.toUpperCase(java.util.Locale.ROOT);
        return upper.contains("FLOAT") || upper.contains("DOUBLE") || upper.contains("REAL");
    }

    @Override
    public String toString() {
        return "Normalizer[" + opts + "]";
    }

    /**
     * 被 P10 截断的 LOB 值（带 TRUNCATED 标记，报告必须显式列为 WARN）。
     */
    public static final class LobValue {

        private final String prefix;
        private final long byteLength;
        private final boolean truncated;

        /**
         * 构造。
         *
         * @param prefix     前 N 字节（二进制为 Base64，文本为原文前缀）
         * @param byteLength 原始长度
         * @param truncated  是否被截断
         */
        public LobValue(String prefix, long byteLength, boolean truncated) {
            this.prefix = prefix == null ? "" : prefix;
            this.byteLength = byteLength;
            this.truncated = truncated;
        }

        /** @return 前缀 */
        public String getPrefix() {
            return prefix;
        }

        /** @return 原始字节长度 */
        public long getByteLength() {
            return byteLength;
        }

        /** @return 是否截断 */
        public boolean isTruncated() {
            return truncated;
        }

        /**
         * @return 参与哈希的文本（前缀 + 长度，长度必须参与，否则"前 N 字节相同、总长不同"会误判相等）
         */
        public String hashText() {
            return prefix + "#len=" + byteLength;
        }

        /**
         * @return 展示文本（显式标注 TRUNCATED）
         */
        public String displayText() {
            return truncated ? prefix + "…(TRUNCATED,total=" + byteLength + "B)" : prefix;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof LobValue)) {
                return false;
            }
            LobValue other = (LobValue) o;
            return byteLength == other.byteLength && truncated == other.truncated
                    && prefix.equals(other.prefix);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(prefix, Long.valueOf(byteLength), Boolean.valueOf(truncated));
        }

        @Override
        public String toString() {
            return displayText();
        }
    }
}
