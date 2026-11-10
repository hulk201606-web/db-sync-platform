package com.dbsync.cutover;

import com.dbsync.common.enums.CutoverState;

/**
 * 一次"状态对齐"的结果（T38 恢复服务的产物）。
 *
 * <p>三种分支对应架构 §4.5.4 的三路判断：
 * <ol>
 *   <li>{@link Branch#EXTERNAL_AT_INTENT}：外部路由已经是 {@code intent}，
 *       说明上次进程在"下发成功、写 confirmed 前"崩了 → 只补 {@code confirmed}；</li>
 *   <li>{@link Branch#EXTERNAL_AT_CONFIRMED}：外部路由还停在 {@code confirmed}，
 *       说明下发没生效 → 重新下发并等 ack；</li>
 *   <li>{@link Branch#DRIFT}：外部既不是 intent 也不是 confirmed（有人手工改过网关/回滚了一半）
 *       → 标 {@code plan_status=INCONSISTENT}，<b>绝不自动推进</b>，必须人工介入。</li>
 * </ol>
 *
 * <p>不可变。
 */
public final class ReconcileResult {

    /** 对齐分支。 */
    public enum Branch {
        /** 外部已在 intent，只补 confirmed。 */
        EXTERNAL_AT_INTENT,
        /** 外部停在 confirmed，需重新下发。 */
        EXTERNAL_AT_CONFIRMED,
        /** 外部漂移，必须人工介入。 */
        DRIFT
    }

    private final long planId;
    private final Branch branch;
    private final CutoverState intentState;
    private final CutoverState confirmedState;
    private final RouteRegistry.Bucket externalBucket;
    private final CutoverState newConfirmed;
    private final long routeVersion;
    private final boolean changed;
    private final String message;

    /**
     * 构造。
     *
     * @param planId        计划 ID
     * @param branch        分支
     * @param intentState   计划的 intent
     * @param confirmedState 计划的 confirmed
     * @param externalBucket 外部实际形态
     * @param newConfirmed  对齐后的 confirmed（DRIFT 分支保持不变）
     * @param routeVersion  路由版本
     * @param changed       是否真的改了库
     * @param message       说明
     */
    public ReconcileResult(long planId, Branch branch, CutoverState intentState,
                           CutoverState confirmedState, RouteRegistry.Bucket externalBucket,
                           CutoverState newConfirmed, long routeVersion, boolean changed,
                           String message) {
        this.planId = planId;
        this.branch = branch;
        this.intentState = intentState;
        this.confirmedState = confirmedState;
        this.externalBucket = externalBucket;
        this.newConfirmed = newConfirmed;
        this.routeVersion = routeVersion;
        this.changed = changed;
        this.message = message;
    }

    /** @return 计划 ID */
    public long getPlanId() {
        return planId;
    }

    /** @return 分支 */
    public Branch getBranch() {
        return branch;
    }

    /** @return intent */
    public CutoverState getIntentState() {
        return intentState;
    }

    /** @return confirmed */
    public CutoverState getConfirmedState() {
        return confirmedState;
    }

    /** @return 外部实际形态 */
    public RouteRegistry.Bucket getExternalBucket() {
        return externalBucket;
    }

    /** @return 对齐后的 confirmed */
    public CutoverState getNewConfirmed() {
        return newConfirmed;
    }

    /** @return 路由版本 */
    public long getRouteVersion() {
        return routeVersion;
    }

    /** @return 是否改动了元数据库 */
    public boolean isChanged() {
        return changed;
    }

    /** @return 说明 */
    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "Reconcile[plan=" + planId + "," + branch + ",intent=" + intentState
                + ",confirmed=" + confirmedState + "→" + newConfirmed + "]";
    }
}
