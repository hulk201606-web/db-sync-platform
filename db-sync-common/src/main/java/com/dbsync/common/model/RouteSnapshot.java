package com.dbsync.common.model;

import com.dbsync.common.enums.RouteTarget;
import java.time.Instant;
import java.util.List;

/**
 * 路由快照（不可变）。SDK 侧只接受 {@code version > localVersion} 的快照（架构 I6）。
 *
 * <p>回滚时**一次**下发"读回源 + 停写目标"的单一快照，避免中间态（架构 §4.5.5）。
 */
public final class RouteSnapshot {

    private final long planId;
    private final long version;
    private final RouteTarget readTarget;
    private final int readGrayRatio;
    private final boolean writeTarget;
    private final int writeGrayRatio;
    private final List<RouteRule> rules;
    private final Instant createdAt;

    /**
     * 构造。
     *
     * @param planId        计划 ID
     * @param version       版本（单调递增）
     * @param readTarget    读目标
     * @param readGrayRatio 读灰度百分比（100 表示全量）
     * @param writeTarget   是否写目标（双写）
     * @param writeGrayRatio 写灰度百分比
     * @param rules         规则列表
     * @param createdAt     创建时间
     */
    public RouteSnapshot(long planId, long version, RouteTarget readTarget, int readGrayRatio,
                         boolean writeTarget, int writeGrayRatio, List<RouteRule> rules, Instant createdAt) {
        this.planId = planId;
        this.version = version;
        this.readTarget = readTarget;
        this.readGrayRatio = readGrayRatio;
        this.writeTarget = writeTarget;
        this.writeGrayRatio = writeGrayRatio;
        this.rules = List.copyOf(rules == null ? List.of() : rules);
        this.createdAt = createdAt;
    }

    /**
     * 全量回源快照（回滚专用）：读回源 + 停写目标，原子生效。
     *
     * @param planId  计划 ID
     * @param version 新版本
     * @param at      时间
     * @return 快照
     */
    public static RouteSnapshot fullSource(long planId, long version, Instant at) {
        return new RouteSnapshot(planId, version, RouteTarget.SOURCE, 100, false, 0, List.of(), at);
    }

    /** @return 计划 ID */
    public long getPlanId() {
        return planId;
    }

    /** @return 版本 */
    public long getVersion() {
        return version;
    }

    /** @return 读目标 */
    public RouteTarget getReadTarget() {
        return readTarget;
    }

    /** @return 读灰度百分比 */
    public int getReadGrayRatio() {
        return readGrayRatio;
    }

    /** @return 是否写目标 */
    public boolean isWriteTarget() {
        return writeTarget;
    }

    /** @return 写灰度百分比 */
    public int getWriteGrayRatio() {
        return writeGrayRatio;
    }

    /** @return 不可变规则列表 */
    public List<RouteRule> getRules() {
        return rules;
    }

    /** @return 创建时间 */
    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "RouteSnapshot[plan=" + planId + ",v=" + version + ",read=" + readTarget
                + "/" + readGrayRatio + "%,write=" + writeTarget + "]";
    }
}
