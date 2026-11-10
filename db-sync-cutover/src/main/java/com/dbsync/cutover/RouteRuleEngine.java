package com.dbsync.cutover;

import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.enums.GateItemCode;
import com.dbsync.common.enums.RouteTarget;
import com.dbsync.common.model.CutoverPlan;
import com.dbsync.common.model.RouteRule;
import com.dbsync.common.model.RouteSnapshot;
import com.dbsync.common.util.HashUtil;
import com.dbsync.common.util.JsonUtil;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 路由规则引擎（T40）：把"灰度百分比 / 表白名单 / 哈希桶 / 组合"解析成读路由目标。
 *
 * <p><b>四个维度</b>（架构 §4.5.3 的灰度维度）：
 * <ul>
 *   <li>{@code ratio}：按主键哈希取模 100 落在 {@code [0, ratio)} 内 → 目标；</li>
 *   <li>{@code tableWhitelist}：表名命中白名单 → 目标；</li>
 *   <li>{@code hashBucket}：{@code fnv1a64(pk) % mod == index} → 目标；</li>
 *   <li><b>组合</b>：同一条规则内配置了多个维度时**全部满足**才算命中（AND），
 *       多条规则之间是"或"（第一条命中的规则决定结果）。</li>
 * </ul>
 *
 * <p><b>为什么按主键哈希而不是随机</b>：同一主键必须始终落到同一侧，否则同一次请求的前后
 * 两次读会读到不同的库，出现"读到自己刚写的旧值"这类难查的幻觉。
 *
 * <p>本类无状态、线程安全。
 */
public class RouteRuleEngine {

    /** 默认哈希桶数量（比例规则的模数）。 */
    private static final int RATIO_MOD = 100;

    /**
     * 解析读路由目标。
     *
     * @param rules 规则列表（按顺序匹配，第一条命中的规则决定结果）
     * @param table 表名（可 null：此时表白名单规则无法命中）
     * @param pk    主键（可 null：此时哈希类规则按 0 处理，结果稳定但不可分散）
     * @return 读目标
     */
    public RouteTarget resolve(List<RouteRule> rules, String table, Object pk) {
        if (rules == null || rules.isEmpty()) {
            return RouteTarget.SOURCE;
        }
        int bucket = hashBucket(pk, RATIO_MOD);
        long hash = pk == null ? 0L : HashUtil.fnv1a64(String.valueOf(pk));
        for (RouteRule rule : rules) {
            if (rule == null || !rule.isEnabled()) {
                continue;
            }
            if (matches(rule, table, bucket, hash)) {
                return RouteTarget.TARGET;
            }
        }
        return RouteTarget.SOURCE;
    }

    /**
     * 全源兜底规则（回滚快照用）。
     *
     * @return ratio=0 的规则
     */
    public RouteRule fallbackToSource() {
        return RouteRule.ratio(0);
    }

    /**
     * 单条规则是否命中。
     *
     * @param rule   规则
     * @param table  表名
     * @param bucket 比例桶（0..99）
     * @param hash   主键 FNV 哈希
     * @return true 表示该规则命中（组合维度必须全部满足）
     */
    private boolean matches(RouteRule rule, String table, int bucket, long hash) {
        boolean any = false;
        if (rule.getRatio() != null) {
            any = true;
            if (bucket >= clampPercent(rule.getRatio().intValue())) {
                return false;
            }
        }
        if (!rule.getTableWhitelist().isEmpty()) {
            any = true;
            if (!containsIgnoreCase(rule.getTableWhitelist(), table)) {
                return false;
            }
        }
        if (rule.getHashBucketMod() != null && rule.getHashBucketMod().intValue() > 0) {
            any = true;
            int mod = rule.getHashBucketMod().intValue();
            int index = rule.getHashBucketIndex() == null ? 0 : rule.getHashBucketIndex().intValue();
            if (Math.floorMod(hash, (long) mod) != Math.floorMod(index, mod)) {
                return false;
            }
        }
        // 三个维度都未配置 → 视为"全目标"（显式配了一条空规则，等价于 100%）
        return any || rule.getRatio() == null;
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
            if (candidate == null) {
                continue;
            }
            String value = candidate.toLowerCase(Locale.ROOT);
            if (value.equals(plain) || value.equals(simple)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 主键 → 桶（0..mod-1，使用同一套 FNV-1a，保证跨语言可复现）。
     *
     * @param pk  主键
     * @param mod 模数
     * @return 桶号
     */
    static int hashBucket(Object pk, int mod) {
        if (mod <= 0) {
            return 0;
        }
        if (pk == null) {
            return 0;
        }
        return (int) Math.floorMod(HashUtil.fnv1a64(String.valueOf(pk)), (long) mod);
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
     * 按目标状态生成下发的规则列表。
     *
     * @param state 目标状态
     * @param ratio 灰度比例（读/写各自的有效比例）
     * @return 规则列表（全源/全目标时为单条 100/0 规则）
     */
    public List<RouteRule> rulesFor(CutoverState state, int ratio) {
        List<RouteRule> rules = new ArrayList<RouteRule>(1);
        switch (state) {
            case GRAY_READ:
            case GRAY_WRITE:
                rules.add(RouteRule.ratio(clampPercent(ratio)));
                break;
            case FULL_TARGET:
            case OBSERVE:
            case DONE:
                rules.add(RouteRule.ratio(100));
                break;
            default:
                rules.add(RouteRule.ratio(0));
                break;
        }
        return rules;
    }

    /**
     * 生成目标状态对应的路由快照（不发布，仅构造）。
     *
     * @param plan     计划
     * @param state    目标状态
     * @param version  版本号
     * @param at       时间
     * @return 快照
     */
    public RouteSnapshot snapshotFor(CutoverPlan plan, CutoverState state, long version,
                                     Instant at) {
        int readRatio = plan.getGrayRatio() <= 0 ? 5 : plan.getGrayRatio();
        int writeRatio = plan.getWriteGrayRatio() <= 0 ? 5 : plan.getWriteGrayRatio();
        switch (state) {
            case GRAY_READ:
                return new RouteSnapshot(plan.getId(), version, RouteTarget.TARGET, readRatio,
                        false, 0, rulesFor(state, readRatio), at);
            case GRAY_WRITE:
                return new RouteSnapshot(plan.getId(), version, RouteTarget.TARGET, 100, true,
                        writeRatio, rulesFor(state, writeRatio), at);
            case FULL_TARGET:
            case OBSERVE:
            case DONE:
                return new RouteSnapshot(plan.getId(), version, RouteTarget.TARGET, 100, true, 100,
                        rulesFor(state, 100), at);
            case DRAFT:
            case PRE_CHECK:
            case PRE_CHECK_FAILED:
            case ROLLED_BACK:
            default:
                return RouteSnapshot.fullSource(plan.getId(), version, at);
        }
    }

    /**
     * 渲染规则（留给审计与 dry-run 展示）。
     *
     * @param rules 规则
     * @return JSON
     */
    public String render(List<RouteRule> rules) {
        List<Map<String, Object>> maps = new ArrayList<Map<String, Object>>();
        if (rules != null) {
            for (RouteRule rule : rules) {
                maps.add(rule.toMap());
            }
        }
        return JsonUtil.toJson(maps);
    }

    /**
     * 默认 ack 超时（编排器用）。
     *
     * @return 30 秒（架构 §4.6.4：ack 超时必须回退 intent）
     */
    public static Duration defaultAckTimeout() {
        return Duration.ofSeconds(30L);
    }

    /**
     * Gate 指标编码是否属于 G1（比对通过率）—— 供报告展示分组。
     *
     * @param code 指标
     * @return true 表示 G1
     */
    public static boolean isCompareMetric(GateItemCode code) {
        return code == GateItemCode.COMPARE_PASS_RATE;
    }
}
