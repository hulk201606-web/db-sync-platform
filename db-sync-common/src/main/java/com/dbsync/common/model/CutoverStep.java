package com.dbsync.common.model;

import com.dbsync.common.enums.CutoverState;

/**
 * 切流步骤留痕（不可变）。每步都写 {@code cutover_step_log}，
 * 含 {@code gateResult} 与 {@code routeVersionBefore/After}，供事后回放"当时为什么允许推进"。
 */
public final class CutoverStep {

    private final long planId;
    private final int stepSeq;
    private final CutoverState fromState;
    private final CutoverState toState;
    private final String action;
    private final String result;
    private final String errorMsg;
    private final long durationMs;
    private final String operator;
    private final long routeVersionBefore;
    private final long routeVersionAfter;
    private final boolean gateSkipped;
    private final String skipReason;
    private final GateResult gateResult;

    /**
     * 构造。
     *
     * @param planId            计划 ID
     * @param stepSeq           步骤序号
     * @param fromState         起始状态
     * @param toState           目标状态
     * @param action            动作（ADVANCE/ROLLBACK/SKIP_GATE/DRY_RUN/RECONCILE/GATE_CHECK）
     * @param result            结果（SUCCESS/FAILED/TIMEOUT）
     * @param errorMsg          错误信息
     * @param durationMs        耗时
     * @param operator          操作人
     * @param routeVersionBefore 快照版本（前）
     * @param routeVersionAfter  快照版本（后）
     * @param gateSkipped       是否跳过 Gate
     * @param skipReason        跳过理由
     * @param gateResult        Gate 判定明细
     */
    public CutoverStep(long planId, int stepSeq, CutoverState fromState, CutoverState toState,
                       String action, String result, String errorMsg, long durationMs, String operator,
                       long routeVersionBefore, long routeVersionAfter, boolean gateSkipped,
                       String skipReason, GateResult gateResult) {
        this.planId = planId;
        this.stepSeq = stepSeq;
        this.fromState = fromState;
        this.toState = toState;
        this.action = action;
        this.result = result;
        this.errorMsg = errorMsg;
        this.durationMs = durationMs;
        this.operator = operator;
        this.routeVersionBefore = routeVersionBefore;
        this.routeVersionAfter = routeVersionAfter;
        this.gateSkipped = gateSkipped;
        this.skipReason = skipReason;
        this.gateResult = gateResult;
    }

    /** @return 计划 ID */
    public long getPlanId() {
        return planId;
    }

    /** @return 步骤序号 */
    public int getStepSeq() {
        return stepSeq;
    }

    /** @return 起始状态 */
    public CutoverState getFromState() {
        return fromState;
    }

    /** @return 目标状态 */
    public CutoverState getToState() {
        return toState;
    }

    /** @return 动作 */
    public String getAction() {
        return action;
    }

    /** @return 结果 */
    public String getResult() {
        return result;
    }

    /** @return 错误信息 */
    public String getErrorMsg() {
        return errorMsg;
    }

    /** @return 耗时毫秒 */
    public long getDurationMs() {
        return durationMs;
    }

    /** @return 操作人 */
    public String getOperator() {
        return operator;
    }

    /** @return 版本（前） */
    public long getRouteVersionBefore() {
        return routeVersionBefore;
    }

    /** @return 版本（后） */
    public long getRouteVersionAfter() {
        return routeVersionAfter;
    }

    /** @return 是否跳过 Gate */
    public boolean isGateSkipped() {
        return gateSkipped;
    }

    /** @return 跳过理由 */
    public String getSkipReason() {
        return skipReason;
    }

    /** @return Gate 判定明细 */
    public GateResult getGateResult() {
        return gateResult;
    }

    /**
     * @return true 表示该步骤成功
     */
    public boolean isSuccess() {
        return "SUCCESS".equals(result);
    }

    @Override
    public String toString() {
        return "Step[" + stepSeq + "," + fromState + "→" + toState + "," + result + "]";
    }
}
