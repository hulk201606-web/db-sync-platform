package com.dbsync.cdc.sdk;

import com.dbsync.common.util.HashUtil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 平台下发的路由快照（SDK 侧解析后的不可变视图）。
 *
 * <p>与平台侧 {@code com.dbsync.common.model.RouteSnapshot} 的区别：那个是"平台内部的强类型对象"，
 * 这个是从 HTTP JSON 解析出来的"SDK 侧副本"，字段更少（只有 SDK 做路由决策需要的），
 * 并且带了 {@code snapshotTtlMs}（陈旧上限）。
 *
 * <p><b>哈希算法必须与平台完全一致</b>（任务提醒的坑）：
 * 平台 {@code RouteRuleEngine.resolve} 用的是
 * {@code Math.floorMod(HashUtil.fnv1a64(String.valueOf(pk)), 100)}，
 * 本类通过 {@link #bucketOf(Object, int)} 复用<b>同一个</b> {@link HashUtil#fnv1a64(String)}，
 * 因此业务侧算出的桶与平台算出的桶必然相同——绝不能用 {@code hashCode()} 或随机数，
 * 那会让"平台说 5% 走目标、业务实际走了 7%"，灰度比例对不上账。
 *
 * <p>不可变、线程安全。
 */
public final class SdkRouteSnapshot {

    /** 无计划时的兜底状态码（全走源库）。 */
    public static final String STATE_NONE = "NONE";

    /** 读/写目标：源库。 */
    public static final String TARGET_SOURCE = "SOURCE";
    /** 读/写目标：目标库。 */
    public static final String TARGET_TARGET = "TARGET";
    /** 写目标：双写（源 + 目标）。 */
    public static final String TARGET_BOTH = "BOTH";

    private static final int RATIO_MOD = 100;

    private final long taskId;
    private final long routeVersion;
    private final String state;
    private final String readFrom;
    private final String writeTo;
    private final int grayRatio;
    private final List<Map<String, Object>> rules;
    private final long snapshotTtlMs;
    private final Instant generatedAt;

    /**
     * 构造。
     *
     * @param taskId       任务 ID
     * @param routeVersion 路由版本（单调递增）
     * @param state        切流状态（{@code NONE} 表示还没有计划）
     * @param readFrom     读目标 {@code SOURCE}/{@code TARGET}
     * @param writeTo      写目标 {@code SOURCE}/{@code BOTH}/{@code TARGET}
     * @param grayRatio    当前生效的灰度百分比
     * @param rules        规则列表（每条形如 {@code {"dim":..,"expr":..,"ratio":..}}）
     * @param snapshotTtlMs 快照陈旧上限（毫秒）
     * @param generatedAt  平台生成时间
     */
    public SdkRouteSnapshot(long taskId, long routeVersion, String state, String readFrom,
                            String writeTo, int grayRatio, List<Map<String, Object>> rules,
                            long snapshotTtlMs, Instant generatedAt) {
        this.taskId = taskId;
        this.routeVersion = routeVersion;
        this.state = state == null ? STATE_NONE : state;
        this.readFrom = readFrom == null ? TARGET_SOURCE : readFrom;
        this.writeTo = writeTo == null ? TARGET_SOURCE : writeTo;
        this.grayRatio = Math.max(0, Math.min(100, grayRatio));
        this.rules = rules == null ? List.of() : List.copyOf(rules);
        this.snapshotTtlMs = snapshotTtlMs;
        this.generatedAt = generatedAt;
    }

    /**
     * 兜底快照：版本 0、状态 {@code NONE}、读写全源库。
     *
     * <p>SDK 在"从未成功拉取过快照"时用它——业务侧必须永远拿到一个可用的决策，
     * 而不是抛异常或返回 null（那会让业务在平台不可用时直接白屏）。
     *
     * @param taskId        任务 ID
     * @param snapshotTtlMs 快照陈旧上限
     * @return 快照
     */
    public static SdkRouteSnapshot fallback(long taskId, long snapshotTtlMs) {
        return new SdkRouteSnapshot(taskId, 0L, STATE_NONE, TARGET_SOURCE, TARGET_SOURCE, 0,
                List.of(), snapshotTtlMs, Instant.EPOCH);
    }

    /**
     * 该表 + 该主键是否命中的"灰度命中"（走目标侧）。
     *
     * <p>判定顺序与平台 {@code RouteRuleEngine.resolve} 同构：
     * 规则按顺序匹配，第一条命中的规则即为结果；三个维度（比例 / 表白名单 / 哈希桶）
     * 在<b>同一条规则内</b>是 AND，多条规则之间是 OR；规则列表为空时回落到
     * 快照的 {@code grayRatio}（全源快照的 {@code grayRatio=0}，因此恒不命中）。
     *
     * @param table 表名（可 null）
     * @param pk    主键（可 null：此时桶为 0，结果稳定但不可分散）
     * @return true 表示走目标侧
     */
    public boolean hitsTarget(String table, Object pk) {
        if (rules.isEmpty()) {
            return pk != null && bucketOf(pk, RATIO_MOD) < grayRatio;
        }
        int bucket = bucketOf(pk, RATIO_MOD);
        long hash = pk == null ? 0L : HashUtil.fnv1a64(String.valueOf(pk));
        for (Map<String, Object> rule : rules) {
            Boolean enabled = boolOf(rule.get("enabled"));
            if (Boolean.FALSE.equals(enabled)) {
                continue;
            }
            if (matches(rule, table, bucket, hash)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 主键 → 桶（0..mod-1）。算法与平台 {@code RouteRuleEngine.hashBucket} 完全一致。
     *
     * @param pk  主键
     * @param mod 模数
     * @return 桶号
     */
    public static int bucketOf(Object pk, int mod) {
        if (mod <= 0 || pk == null) {
            return 0;
        }
        return (int) Math.floorMod(HashUtil.fnv1a64(String.valueOf(pk)), (long) mod);
    }

    /**
     * 单条规则是否命中（同一条规则内多维度 AND）。
     *
     * @param rule   规则
     * @param table  表名
     * @param bucket 比例桶
     * @param hash   主键 FNV 哈希
     * @return true 命中
     */
    private boolean matches(Map<String, Object> rule, String table, int bucket, long hash) {
        boolean any = false;
        Object ratio = rule.get("ratio");
        if (ratio instanceof Number) {
            any = true;
            if (bucket >= clampPercent(((Number) ratio).intValue())) {
                return false;
            }
        }
        Object whitelist = rule.get("tableWhitelist");
        if (whitelist instanceof List && !((List<?>) whitelist).isEmpty()) {
            any = true;
            List<String> names = new ArrayList<>();
            for (Object item : (List<?>) whitelist) {
                if (item != null) {
                    names.add(String.valueOf(item));
                }
            }
            if (!containsIgnoreCase(names, table)) {
                return false;
            }
        }
        Object hashBucket = rule.get("hashBucket");
        if (hashBucket instanceof Map) {
            Map<?, ?> spec = (Map<?, ?>) hashBucket;
            int mod = intOf(spec.get("mod"), 0);
            int index = intOf(spec.get("index"), 0);
            if (mod > 0) {
                any = true;
                if (Math.floorMod(hash, (long) mod) != Math.floorMod(index, mod)) {
                    return false;
                }
            }
        }
        return any;
    }

    /**
     * 表名比对（大小写不敏感，且支持不带 schema 的简名匹配 {@code schema.table}）。
     *
     * @param whitelist 白名单
     * @param table     表名
     * @return true 命中
     */
    private static boolean containsIgnoreCase(List<String> whitelist, String table) {
        if (table == null || table.isBlank()) {
            return false;
        }
        String plain = table.toLowerCase(Locale.ROOT);
        String simple = plain.contains(".")
                ? plain.substring(plain.lastIndexOf('.') + 1) : plain;
        for (String candidate : whitelist) {
            String value = candidate.toLowerCase(Locale.ROOT);
            if (value.equals(plain) || value.equals(simple)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 百分比收敛。
     *
     * @param value 原值
     * @return 0..100
     */
    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    /**
     * 宽松布尔解析。
     *
     * @param value 原值
     * @return 布尔；无法判定返回 {@code null}
     */
    private static Boolean boolOf(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        return Boolean.valueOf(!"false".equalsIgnoreCase(text) && !"0".equals(text));
    }

    /**
     * 宽松整型解析。
     *
     * @param value        原值
     * @param defaultValue 默认值
     * @return 整数
     */
    private static int intOf(Object value, int defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** @return 任务 ID */
    public long getTaskId() {
        return taskId;
    }

    /** @return 路由版本 */
    public long getRouteVersion() {
        return routeVersion;
    }

    /** @return 切流状态 */
    public String getState() {
        return state;
    }

    /** @return 读目标 */
    public String getReadFrom() {
        return readFrom;
    }

    /** @return 写目标 */
    public String getWriteTo() {
        return writeTo;
    }

    /** @return 灰度百分比 */
    public int getGrayRatio() {
        return grayRatio;
    }

    /** @return 规则列表 */
    public List<Map<String, Object>> getRules() {
        return rules;
    }

    /** @return 快照陈旧上限（毫秒） */
    public long getSnapshotTtlMs() {
        return snapshotTtlMs;
    }

    /** @return 平台生成时间 */
    public Instant getGeneratedAt() {
        return generatedAt;
    }

    @Override
    public String toString() {
        return "SdkRouteSnapshot[task=" + taskId + ",v=" + routeVersion + ",state=" + state
                + ",read=" + readFrom + "/" + grayRatio + "%,write=" + writeTo + "]";
    }
}
