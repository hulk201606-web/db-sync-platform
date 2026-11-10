package com.dbsync.common.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 路由规则（不可变）：4 个维度 ratio / tableWhitelist / hashBucket / 组合。
 */
public final class RouteRule {

    private final Integer ratio;
    private final java.util.List<String> tableWhitelist;
    private final Integer hashBucketMod;
    private final Integer hashBucketIndex;
    private final boolean enabled;

    /**
     * 构造。
     *
     * @param ratio           读灰度百分比 0..100，null 表示未配置
     * @param tableWhitelist  表白名单（走目标），null 表示未配置
     * @param hashBucketMod   哈希桶模数，null 表示未配置
     * @param hashBucketIndex 命中桶下标（0-based）
     * @param enabled         是否启用
     */
    public RouteRule(Integer ratio, java.util.List<String> tableWhitelist, Integer hashBucketMod,
                     Integer hashBucketIndex, boolean enabled) {
        this.ratio = ratio;
        this.tableWhitelist = tableWhitelist == null ? java.util.List.of() : java.util.List.copyOf(tableWhitelist);
        this.hashBucketMod = hashBucketMod;
        this.hashBucketIndex = hashBucketIndex;
        this.enabled = enabled;
    }

    /**
     * 比例规则。
     *
     * @param ratio 百分比 0..100
     * @return 规则
     */
    public static RouteRule ratio(int ratio) {
        return new RouteRule(ratio, null, null, null, true);
    }

    /**
     * 表白名单规则。
     *
     * @param tables 表名列表
     * @return 规则
     */
    public static RouteRule tables(java.util.List<String> tables) {
        return new RouteRule(null, tables, null, null, true);
    }

    /**
     * 哈希桶规则。
     *
     * @param mod   模数
     * @param index 命中桶
     * @return 规则
     */
    public static RouteRule hashBucket(int mod, int index) {
        return new RouteRule(null, null, mod, index, true);
    }

    /** @return 比例，可能为 null */
    public Integer getRatio() {
        return ratio;
    }

    /** @return 表白名单 */
    public java.util.List<String> getTableWhitelist() {
        return tableWhitelist;
    }

    /** @return 哈希模数，可能为 null */
    public Integer getHashBucketMod() {
        return hashBucketMod;
    }

    /** @return 哈希桶下标，可能为 null */
    public Integer getHashBucketIndex() {
        return hashBucketIndex;
    }

    /** @return 是否启用 */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 转为落库 JSON 视图（{@code cutover_plan.gray_dimensions}）。
     *
     * @return 有序 map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ratio", ratio);
        map.put("tableWhitelist", tableWhitelist);
        map.put("hashBucket", hashBucketMod == null ? null
                : Map.of("mod", hashBucketMod, "index", hashBucketIndex));
        return map;
    }

    @Override
    public String toString() {
        return "RouteRule[ratio=" + ratio + ",tables=" + tableWhitelist.size()
                + ",hash=" + hashBucketMod + "/" + hashBucketIndex + "]";
    }
}
