package com.dbsync.api.dto.cutover;

import com.dbsync.common.model.CutoverStep;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 切流步骤视图。
 *
 * @param planId            计划 ID
 * @param stepSeq           步骤序号
 * @param fromState         源状态
 * @param toState           目标状态
 * @param action            动作（ADVANCE/ROLLBACK）
 * @param result            结果（SUCCESS/BLOCKED/TIMEOUT）
 * @param errorMsg          错误信息
 * @param durationMs        耗时
 * @param operator          操作人
 * @param routeVersionBefore 变更前版本
 * @param routeVersionAfter  变更后版本
 * @param gateSkipped       是否跳过了 Gate
 * @param skipReason        跳过理由
 */
public record CutoverStepView(long planId, int stepSeq, String fromState, String toState,
                              String action, String result, String errorMsg, long durationMs,
                              String operator, long routeVersionBefore, long routeVersionAfter,
                              boolean gateSkipped, String skipReason) {

    /**
     * 从模型转换。
     *
     * @param step 步骤
     * @return 视图
     */
    public static CutoverStepView of(CutoverStep step) {
        return new CutoverStepView(step.getPlanId(), step.getStepSeq(),
                step.getFromState() == null ? null : step.getFromState().name(),
                step.getToState() == null ? null : step.getToState().name(), step.getAction(),
                step.getResult(), step.getErrorMsg(), step.getDurationMs(), step.getOperator(),
                step.getRouteVersionBefore(), step.getRouteVersionAfter(), step.isGateSkipped(),
                step.getSkipReason());
    }

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("planId", planId);
        map.put("stepSeq", stepSeq);
        map.put("fromState", fromState);
        map.put("toState", toState);
        map.put("action", action);
        map.put("result", result);
        map.put("errorMsg", errorMsg);
        map.put("durationMs", durationMs);
        map.put("operator", operator);
        map.put("routeVersionBefore", routeVersionBefore);
        map.put("routeVersionAfter", routeVersionAfter);
        map.put("gateSkipped", gateSkipped);
        map.put("skipReason", skipReason);
        return map;
    }
}
