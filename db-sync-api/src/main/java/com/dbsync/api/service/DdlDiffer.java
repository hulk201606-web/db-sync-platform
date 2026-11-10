package com.dbsync.api.service;

import com.dbsync.api.dto.mapping.DdlDiffView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DDL 差异判定（PRD MAP-05）——<b>纯函数</b>，不碰数据库、不碰方言。
 *
 * <p>把"两个类型串是否兼容"集中到一处，是本类存在的唯一理由：判定规则一旦分散到
 * 多个调用点，就必然出现"这里报不一致、那里报一致"的自相矛盾。
 *
 * <p><b>头号目标是压掉假阳性</b>。字面比较会产生大量噪音：
 * {@code DECIMAL(38,10)} 与 {@code NUMERIC(38,10)} 是同一种类型、
 * {@code integer} 与 {@code INT} 也是、{@code VARCHAR(64)} 与 {@code varchar(64)} 更是。
 * 一份"全是红条"的报告等于没有报告，运维只会学会忽略它。因此判定前先做归一化：
 * <ol>
 *   <li>去空白、转大写（{@code varchar} ≡ {@code VARCHAR}）；</li>
 *   <li>基础类型同义词归一（{@code NUMERIC}/{@code NUMBER}/{@code DEC} → {@code DECIMAL}，
 *       {@code INT}/{@code INTEGER} → {@code INTEGER}，{@code DATETIME} → {@code TIMESTAMP} 等，
 *       见 {@link #CANONICAL}）；</li>
 *   <li>括号内的 {@code BYTE}/{@code CHAR} 长度单位忽略，只取 precision/scale；</li>
 *   <li>任一侧缺长度精度（如 {@code VARCHAR} vs {@code VARCHAR(64)}）时判 {@code OK}——
 *       "不知道"不等于"不一致"，宁可漏报也不制造需要人工逐条核对的噪音；</li>
 *   <li>数值类型只在目标<b>确实更窄</b>（精度或小数位小于生成值）时才报
 *       {@code TYPE_MISMATCH}：目标更宽不会截断，不算问题。</li>
 * </ol>
 *
 * <p>字符类型同理：只有目标长度<b>短于</b>生成值才报不一致（会截断）。
 */
public final class DdlDiffer {

    /** 目标已有该列且类型兼容。 */
    public static final String STATUS_OK = "OK";
    /** 目标缺该列。 */
    public static final String STATUS_NEW = "NEW";
    /** 列存在但类型不兼容（可能截断或写入失败）。 */
    public static final String STATUS_TYPE_MISMATCH = "TYPE_MISMATCH";
    /** 目标多出的列（源没有）。 */
    public static final String STATUS_EXTRA = "EXTRA";

    /** 索引已在目标库存在。 */
    public static final String INDEX_OK = "OK";
    /** 生成 DDL 声明的索引在目标库缺失。 */
    public static final String INDEX_MISSING = "MISSING";

    /**
     * 基础类型同义词表（key/value 均为大写）。
     *
     * <p>只收"同一种类型的不同写法"，<b>不</b>收"相近但有差异"的类型
     * （例如 {@code BINARY}/{@code VARBINARY} 有补空格语义差异，故意不合并）。
     */
    private static final Map<String, String> CANONICAL = canonicalTypes();

    /** 参与"精度/小数位"比较的数值型基础类型。 */
    private static final List<String> NUMERIC = List.of("DECIMAL", "INTEGER", "BIGINT",
            "SMALLINT", "TINYINT", "DOUBLE", "FLOAT", "REAL");

    /** 参与"长度"比较的字符型基础类型。 */
    private static final List<String> CHARACTER = List.of("VARCHAR", "CHAR");

    private DdlDiffer() {
    }

    /**
     * 判定单列的差异。
     *
     * @param name          列名
     * @param sourceType    源类型串（可 null）
     * @param generatedType 生成 DDL 里的目标类型（可 null）
     * @param targetType    目标库现有类型；<b>null 表示目标缺该列</b>
     * @return 列差异
     */
    public static DdlDiffView.ColumnDiff column(String name, String sourceType, String generatedType,
                                                String targetType) {
        if (targetType == null || targetType.isBlank()) {
            return new DdlDiffView.ColumnDiff(name, sourceType, generatedType, null, STATUS_NEW,
                    "目标缺该列");
        }
        if (generatedType == null || generatedType.isBlank()) {
            // 生成侧没有类型信息（理论上不会发生）：无法比较，不制造假阳性
            return new DdlDiffView.ColumnDiff(name, sourceType, generatedType, targetType, STATUS_OK,
                    null);
        }
        TypeSpec expected = parse(generatedType);
        TypeSpec actual = parse(targetType);
        if (!expected.base.equals(actual.base)) {
            return new DdlDiffView.ColumnDiff(name, sourceType, generatedType, targetType,
                    STATUS_TYPE_MISMATCH, "目标类型不同：" + generatedType + " ≠ " + targetType);
        }
        String problem = compareSameBase(expected, actual, targetType);
        if (problem == null) {
            return new DdlDiffView.ColumnDiff(name, sourceType, generatedType, targetType, STATUS_OK,
                    null);
        }
        return new DdlDiffView.ColumnDiff(name, sourceType, generatedType, targetType,
                STATUS_TYPE_MISMATCH, problem);
    }

    /**
     * 判定"目标多出来的列"。
     *
     * @param name       列名
     * @param targetType 目标库现有类型
     * @return 列差异（状态 {@link #STATUS_EXTRA}）
     */
    public static DdlDiffView.ColumnDiff extraColumn(String name, String targetType) {
        return new DdlDiffView.ColumnDiff(name, null, null, targetType, STATUS_EXTRA,
                "目标多出的列（源表没有）");
    }

    /**
     * 归一化类型串（仅用于比较与日志，不作为 DDL 输出）。
     *
     * @param raw 原始类型串（如 {@code numeric(38, 10)}）
     * @return 归一化结果（如 {@code DECIMAL(38,10)}）
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        TypeSpec spec = parse(raw);
        if (spec.precision == null) {
            return spec.base;
        }
        if (spec.scale == null) {
            return spec.base + "(" + spec.precision + ")";
        }
        return spec.base + "(" + spec.precision + "," + spec.scale + ")";
    }

    /**
     * 两个类型串归一化后是否等价（对外的单一判定入口，与 {@link #column} 同一套规则）。
     *
     * @param left  左类型
     * @param right 右类型
     * @return 等价返回 true
     */
    public static boolean sameType(String left, String right) {
        if (left == null || right == null) {
            return true;
        }
        return STATUS_OK.equals(column("x", null, left, right).status());
    }

    /**
     * 同一基础类型下的精度/长度比较。
     *
     * @param expected   生成侧类型
     * @param actual     目标侧类型
     * @param targetType 目标类型原文（拼说明用）
     * @return null 表示兼容；否则返回不一致原因
     */
    private static String compareSameBase(TypeSpec expected, TypeSpec actual, String targetType) {
        if (NUMERIC.contains(expected.base)) {
            if (expected.precision == null || actual.precision == null) {
                return null;
            }
            int expectedScale = expected.scale == null ? 0 : expected.scale.intValue();
            int actualScale = actual.scale == null ? 0 : actual.scale.intValue();
            if (actual.precision.intValue() >= expected.precision.intValue()
                    && actualScale >= expectedScale) {
                return null;
            }
            return "目标精度更小，可能截断：" + expected.base + "(" + expected.precision + ","
                    + expectedScale + ") → " + targetType;
        }
        if (CHARACTER.contains(expected.base)) {
            if (expected.precision == null || actual.precision == null) {
                return null;
            }
            if (actual.precision.intValue() >= expected.precision.intValue()) {
                return null;
            }
            return "目标长度更短，可能截断：" + expected.base + "(" + expected.precision + ") → "
                    + targetType;
        }
        // 日期/布尔/大字段等：基础类型一致即可，长度精度无业务含义
        return null;
    }

    /**
     * 解析类型串为"基础类型 + 精度 + 小数位"。
     *
     * @param raw 原始类型串
     * @return 解析结果（永不为 null）
     */
    static TypeSpec parse(String raw) {
        String text = raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        String base = text;
        String args = null;
        int open = text.indexOf('(');
        if (open >= 0) {
            base = text.substring(0, open).trim();
            int close = text.lastIndexOf(')');
            args = close > open ? text.substring(open + 1, close).trim()
                    : text.substring(open + 1).trim();
        }
        base = canonical(base);
        Integer precision = null;
        Integer scale = null;
        if (args != null && !args.isEmpty()) {
            String[] parts = args.split(",");
            precision = leadingInt(parts.length > 0 ? parts[0] : null);
            scale = parts.length > 1 ? leadingInt(parts[1]) : null;
        }
        return new TypeSpec(base, precision, scale);
    }

    /**
     * 基础类型归一化（同一类型的多种写法收敛到一种）。
     *
     * @param base 大写基础类型
     * @return 归一化后的基础类型
     */
    private static String canonical(String base) {
        String hit = CANONICAL.get(base);
        return hit == null ? base : hit;
    }

    /**
     * 取字符串开头的十进制数字（{@code "38 BYTE" → 38}）。
     *
     * @param value 片段
     * @return 数字；无数字返回 null
     */
    private static Integer leadingInt(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        int end = 0;
        while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return null;
        }
        try {
            return Integer.valueOf(trimmed.substring(0, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * @return 同义词表（不可变）
     */
    private static Map<String, String> canonicalTypes() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("NUMERIC", "DECIMAL");
        map.put("NUMBER", "DECIMAL");
        map.put("DEC", "DECIMAL");
        map.put("FIXED", "DECIMAL");
        map.put("INT", "INTEGER");
        map.put("INT4", "INTEGER");
        map.put("MEDIUMINT", "INTEGER");
        map.put("INT8", "BIGINT");
        map.put("INT2", "SMALLINT");
        map.put("VARCHAR2", "VARCHAR");
        map.put("NVARCHAR", "VARCHAR");
        map.put("NVARCHAR2", "VARCHAR");
        map.put("CHARACTER VARYING", "VARCHAR");
        map.put("CHARACTER", "CHAR");
        map.put("NCHAR", "CHAR");
        map.put("DOUBLE PRECISION", "DOUBLE");
        map.put("FLOAT8", "DOUBLE");
        map.put("FLOAT4", "FLOAT");
        map.put("DATETIME", "TIMESTAMP");
        map.put("TIMESTAMP WITHOUT TIME ZONE", "TIMESTAMP");
        map.put("TIMESTAMP WITH TIME ZONE", "TIMESTAMP");
        map.put("BOOL", "BOOLEAN");
        map.put("BIT", "BOOLEAN");
        map.put("TEXT", "CLOB");
        map.put("TINYTEXT", "CLOB");
        map.put("MEDIUMTEXT", "CLOB");
        map.put("LONGTEXT", "CLOB");
        map.put("NTEXT", "CLOB");
        map.put("BYTEA", "BLOB");
        map.put("TINYBLOB", "BLOB");
        map.put("MEDIUMBLOB", "BLOB");
        map.put("LONGBLOB", "BLOB");
        return Map.copyOf(map);
    }

    /**
     * 解析后的类型：基础类型 + 精度 + 小数位。
     *
     * @param base      归一化基础类型
     * @param precision 精度/长度（可 null 表示未声明）
     * @param scale     小数位（可 null 表示未声明）
     */
    static final class TypeSpec {

        private final String base;
        private final Integer precision;
        private final Integer scale;

        /**
         * 构造。
         *
         * @param base      基础类型
         * @param precision 精度/长度
         * @param scale     小数位
         */
        TypeSpec(String base, Integer precision, Integer scale) {
            this.base = base;
            this.precision = precision;
            this.scale = scale;
        }

        @Override
        public String toString() {
            return base + (precision == null ? "" : "(" + precision
                    + (scale == null ? "" : "," + scale) + ")");
        }
    }
}
