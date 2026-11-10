package com.dbsync.api.dto.cutover;

import com.dbsync.common.model.CutoverPlan;
import com.dbsync.common.options.GateThresholds;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 切流计划视图。
 *
 * @param id            计划 ID
 * @param planNo        计划编号
 * @param taskId        任务 ID
 * @param name          名称
 * @param intentState   意图状态（I5）
 * @param confirmedState 已确认状态（I6）
 * @param routeVersion  路由版本（I7）
 * @param planStatus    计划状态（ACTIVE/INCONSISTENT/FROZEN/CLOSED）
 * @param grayRatio     读灰度
 * @param writeGrayRatio 写灰度
 * @param rollbackReason 回滚原因
 * @param inconsistent  intent 与 confirmed 是否不一致
 * @param thresholds    Gate 阈值快照
 */
public record CutoverPlanView(long id, String planNo, long taskId, String name, String intentState,
                              String confirmedState, long routeVersion, String planStatus,
                              int grayRatio, int writeGrayRatio, String rollbackReason,
                              boolean inconsistent, Map<String, Object> thresholds) {

    /**
     * 从模型转换。
     *
     * @param plan 计划
     * @return 视图
     */
    public static CutoverPlanView of(CutoverPlan plan) {
        GateThresholds t = plan.getGateThresholds();
        Map<String, Object> th = new LinkedHashMap<>();
        th.put("comparePassRate", Double.valueOf(t.getComparePassRate()));
        th.put("lagP95Ms", Long.valueOf(t.getLagP95Ms()));
        th.put("dualWriteFailRate", Double.valueOf(t.getDualWriteFailRate()));
        th.put("openDeadLetters", Long.valueOf(t.getOpenDeadLetters()));
        th.put("compareResultMaxAgeMinutes", Long.valueOf(t.getCompareResultMaxAgeMinutes()));
        return new CutoverPlanView(plan.getId(), plan.getPlanNo(), plan.getTaskId(),
                plan.getName(), plan.getIntentState().name(), plan.getConfirmedState().name(),
                plan.getRouteVersion(), plan.getPlanStatus(), plan.getGrayRatio(),
                plan.getWriteGrayRatio(), plan.getRollbackReason(), plan.isInconsistent(), th);
    }

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("planNo", planNo);
        map.put("taskId", taskId);
        map.put("name", name);
        map.put("intentState", intentState);
        map.put("confirmedState", confirmedState);
        map.put("routeVersion", routeVersion);
        map.put("planStatus", planStatus);
        map.put("grayRatio", grayRatio);
        map.put("writeGrayRatio", writeGrayRatio);
        map.put("rollbackReason", rollbackReason);
        map.put("inconsistent", inconsistent);
        map.put("thresholds", thresholds);
        return map;
    }
}
