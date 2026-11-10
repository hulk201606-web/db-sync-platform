package com.dbsync.common.util;

import com.dbsync.common.enums.RangeKind;
import java.math.BigDecimal;
import java.util.Comparator;

/**
 * 主键比较工具：把数据库中读到的任意类型主键统一成可比形式。
 *
 * <p>跨库比较的坑：Oracle 的 {@code NUMBER} 读出来是 {@link BigDecimal}，
 * MySQL 的 {@code BIGINT} 是 {@link Long}，PG 的 {@code bigint} 是 {@link Long}，
 * 字符主键是 {@link String}。直接 {@code Comparable#compareTo} 会抛 {@link ClassCastException}。
 */
public final class PkRangeUtil {

    /** 数值比较器（跨类型安全）。 */
    public static final Comparator<Object> NUMERIC_COMPARATOR = new Comparator<Object>() {
        @Override
        public int compare(Object a, Object b) {
            return toBigDecimal(a).compareTo(toBigDecimal(b));
        }
    };

    /** 字符串比较器（按 Unicode 序，与各库默认 collation 近似）。 */
    public static final Comparator<Object> STRING_COMPARATOR = new Comparator<Object>() {
        @Override
        public int compare(Object a, Object b) {
            return String.valueOf(a).compareTo(String.valueOf(b));
        }
    };

    private PkRangeUtil() {
    }

    /**
     * 按区间类型取比较器。
     *
     * @param kind 区间类型
     * @return 比较器
     */
    public static Comparator<Object> comparator(RangeKind kind) {
        return kind == RangeKind.STRING ? STRING_COMPARATOR : NUMERIC_COMPARATOR;
    }

    /**
     * 通用比较（自动识别数值/字符串）。
     *
     * @param a 左值
     * @param b 右值
     * @return 负数/0/正数
     */
    public static int compare(Object a, Object b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        if (a instanceof Number && b instanceof Number) {
            return NUMERIC_COMPARATOR.compare(a, b);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    /**
     * 宽松转 BigDecimal。
     *
     * @param v 值
     * @return BigDecimal；null/空串返回 0
     */
    public static BigDecimal toBigDecimal(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        if (v instanceof Number) {
            return HashUtil.toBigDecimal((Number) v);
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? BigDecimal.ZERO : new BigDecimal(s);
    }

    /**
     * 按区间类型解析字符串形式的主键边界。
     *
     * @param raw  字符串（可为 null）
     * @param kind 区间类型
     * @return 解析后的对象；null 原样返回
     */
    public static Object parse(String raw, RangeKind kind) {
        if (raw == null) {
            return null;
        }
        if (kind == RangeKind.STRING) {
            return raw;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    /**
     * 判断新值是否相对旧值<b>回退</b>（回退必须被拒绝，E3005）。
     *
     * @param previous 旧值（null 表示无旧值）
     * @param next     新值
     * @param kind     区间类型
     * @return true 表示回退
     */
    public static boolean isRegression(String previous, String next, RangeKind kind) {
        if (previous == null) {
            return false;
        }
        if (next == null) {
            return true;
        }
        return compare(parse(next, kind), parse(previous, kind)) < 0;
    }
}
