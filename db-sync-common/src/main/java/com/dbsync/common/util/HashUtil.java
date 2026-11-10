package com.dbsync.common.util;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 哈希工具。
 *
 * <p>{@link #pkHash(List)} <b>必须先归一化数值</b>：{@code 1L}、{@code 1}、
 * {@code BigDecimal("1.00")} 必须落到同一个哈希，否则同一主键会落到不同保序组，
 * 组内串行就失效（架构 §4.3.2 前提）。
 */
public final class HashUtil {

    private HashUtil() {
    }

    /**
     * MD5（十六进制小写）。
     *
     * @param bytes 输入
     * @return 32 位十六进制串
     */
    public static String md5Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(bytes == null ? new byte[0] : bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    /**
     * FNV-1a 64 位。
     *
     * @param s 输入字符串
     * @return 64 位哈希（可为负）
     */
    public static long fnv1a64(String s) {
        long hash = 0xcbf29ce484222325L;
        byte[] data = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        for (byte b : data) {
            hash ^= (b & 0xFF);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /**
     * 主键元组哈希（数值归一化后参与）。
     *
     * @param normalizedPkTuple 归一化后的主键值（null 元素按哨兵处理）
     * @return 64 位哈希
     */
    public static long pkHash(List<Object> normalizedPkTuple) {
        StringBuilder sb = new StringBuilder();
        if (normalizedPkTuple != null) {
            for (Object v : normalizedPkTuple) {
                sb.append(canonical(v)).append('\u0000');
            }
        }
        return fnv1a64(sb.toString());
    }

    /**
     * 值的规范化字符串形式（数值统一为去尾零的十进制字符串）。
     *
     * @param v 值
     * @return 规范串
     */
    public static String canonical(Object v) {
        if (v == null) {
            return "\u0000NULL";
        }
        if (v instanceof Number) {
            return toBigDecimal((Number) v).stripTrailingZeros().toPlainString();
        }
        if (v instanceof byte[]) {
            return java.util.Base64.getEncoder().encodeToString((byte[]) v);
        }
        return String.valueOf(v);
    }

    /**
     * Number → BigDecimal（不经 double，避免精度丢失）。
     *
     * @param n 数值
     * @return BigDecimal
     */
    public static BigDecimal toBigDecimal(Number n) {
        if (n instanceof BigDecimal) {
            return (BigDecimal) n;
        }
        if (n instanceof java.math.BigInteger) {
            return new BigDecimal((java.math.BigInteger) n);
        }
        if (n instanceof Double || n instanceof Float) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return new BigDecimal(n.toString());
    }

    /**
     * 对字符串取 32 位正整数桶号（用于灰度哈希分桶）。
     *
     * @param s 输入
     * @return 非负哈希
     */
    public static int bucketOf(String s) {
        return Math.floorMod((int) (fnv1a64(s) ^ (fnv1a64(s) >>> 32)), Integer.MAX_VALUE);
    }
}
