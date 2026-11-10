package com.dbsync.api.dto.cutover;

import com.dbsync.cutover.DryRunReport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * dry-run 视图（T41：只列步骤不执行）。
 *
 * @param planId           计划 ID
 * @param currentState     当前 intent 状态
 * @param targetState      推进目标状态
 * @param allowed          是否允许推进
 * @param gatePass         Gate 是否通过（null 表示不需要判卡点）
 * @param nextRouteVersion 将下发的路由版本
 * @param steps            将执行的步骤
 * @param generatedAt      生成时间
 */
public record DryRunView(long planId, String currentState, String targetState, boolean allowed,
                         Boolean gatePass, long nextRouteVersion, List<String> steps,
                         String generatedAt) {

    /**
     * 从报告转换。
     *
     * @param report 报告
     * @return 视图
     */
    public static DryRunView of(DryRunReport report) {
        return new DryRunView(report.getPlanId(), report.getCurrentState(), report.getTargetState(),
                report.isAllowed(),
                report.getGateResult() == null ? null
                        : Boolean.valueOf(report.getGateResult().isPass()),
                report.getNextRouteVersion(), report.getSteps(),
                report.getGeneratedAt() == null ? null : report.getGeneratedAt().toString());
    }

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("planId", planId);
        map.put("currentState", currentState);
        map.put("targetState", targetState);
        map.put("allowed", allowed);
        map.put("gatePass", gatePass);
        map.put("nextRouteVersion", nextRouteVersion);
        map.put("steps", steps);
        map.put("generatedAt", generatedAt);
        return map;
    }
}
