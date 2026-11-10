package com.dbsync.common.model;

import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.options.GateThresholds;

/**
 * 切流计划（可变对象：两段式状态 + 路由版本由 DAO 推进）。
 *
 * <p>不变量：
 * <ul>
 *   <li>I5：{@code confirmedState} 只能等于 {@code intentState} 或其前驱；</li>
 *   <li>I7：{@code intentState != confirmedState} 时禁止推进下一步。</li>
 * </ul>
 */
public final class CutoverPlan {

    private final long id;
    private final String planNo;
    private final long taskId;
    private final String name;
    private final GateThresholds gateThresholds;
    private CutoverState intentState;
    private CutoverState confirmedState;
    private long routeVersion;
    private String planStatus;
    private int grayRatio;
    private int writeGrayRatio;
    private String rollbackReason;

    /**
     * 构造。
     *
     * @param id             计划 ID
     * @param planNo         计划编号
     * @param taskId         任务 ID
     * @param name           名称
     * @param intentState    意图状态
     * @param confirmedState 已确认状态
     * @param routeVersion   路由版本
     * @param planStatus     计划状态（ACTIVE/INCONSISTENT/FROZEN/CLOSED）
     * @param gateThresholds Gate 阈值
     * @param grayRatio      读灰度百分比
     * @param writeGrayRatio 写灰度百分比
     */
    public CutoverPlan(long id, String planNo, long taskId, String name, CutoverState intentState,
                       CutoverState confirmedState, long routeVersion, String planStatus,
                       GateThresholds gateThresholds, int grayRatio, int writeGrayRatio) {
        this.id = id;
        this.planNo = planNo;
        this.taskId = taskId;
        this.name = name;
        this.intentState = intentState == null ? CutoverState.DRAFT : intentState;
        this.confirmedState = confirmedState == null ? CutoverState.DRAFT : confirmedState;
        this.routeVersion = routeVersion;
        this.planStatus = planStatus == null ? "ACTIVE" : planStatus;
        this.gateThresholds = gateThresholds == null ? GateThresholds.defaults() : gateThresholds;
        this.grayRatio = grayRatio;
        this.writeGrayRatio = writeGrayRatio;
    }

    /** @return 计划 ID */
    public long getId() {
        return id;
    }

    /** @return 计划编号 */
    public String getPlanNo() {
        return planNo;
    }

    /** @return 任务 ID */
    public long getTaskId() {
        return taskId;
    }

    /** @return 名称 */
    public String getName() {
        return name;
    }

    /** @return 意图状态 */
    public CutoverState getIntentState() {
        return intentState;
    }

    /**
     * 设置意图状态。
     *
     * @param intentState 新状态
     */
    public void setIntentState(CutoverState intentState) {
        this.intentState = intentState;
    }

    /** @return 已确认状态 */
    public CutoverState getConfirmedState() {
        return confirmedState;
    }

    /**
     * 设置已确认状态。
     *
     * @param confirmedState 新状态
     */
    public void setConfirmedState(CutoverState confirmedState) {
        this.confirmedState = confirmedState;
    }

    /** @return 路由版本 */
    public long getRouteVersion() {
        return routeVersion;
    }

    /**
     * 设置路由版本。
     *
     * @param routeVersion 新版本
     */
    public void setRouteVersion(long routeVersion) {
        this.routeVersion = routeVersion;
    }

    /** @return 计划状态 */
    public String getPlanStatus() {
        return planStatus;
    }

    /**
     * 设置计划状态。
     *
     * @param planStatus 新状态
     */
    public void setPlanStatus(String planStatus) {
        this.planStatus = planStatus;
    }

    /** @return 读灰度百分比 */
    public int getGrayRatio() {
        return grayRatio;
    }

    /**
     * 设置读灰度百分比。
     *
     * @param grayRatio 百分比
     */
    public void setGrayRatio(int grayRatio) {
        this.grayRatio = grayRatio;
    }

    /** @return 写灰度百分比 */
    public int getWriteGrayRatio() {
        return writeGrayRatio;
    }

    /**
     * 设置写灰度百分比。
     *
     * @param writeGrayRatio 百分比
     */
    public void setWriteGrayRatio(int writeGrayRatio) {
        this.writeGrayRatio = writeGrayRatio;
    }

    /** @return Gate 阈值 */
    public GateThresholds getGateThresholds() {
        return gateThresholds;
    }

    /** @return 回滚原因 */
    public String getRollbackReason() {
        return rollbackReason;
    }

    /**
     * 设置回滚原因。
     *
     * @param rollbackReason 原因
     */
    public void setRollbackReason(String rollbackReason) {
        this.rollbackReason = rollbackReason;
    }

    /**
     * @return true 表示意图与确认不一致（禁止推进，I7）
     */
    public boolean isInconsistent() {
        return intentState != confirmedState;
    }

    @Override
    public String toString() {
        return "CutoverPlan#" + id + "[" + planNo + ",intent=" + intentState
                + ",confirmed=" + confirmedState + ",v=" + routeVersion + "]";
    }
}
