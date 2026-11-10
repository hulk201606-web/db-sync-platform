package com.dbsync.api.dto.migration;

import com.dbsync.common.model.PrecheckReport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预检结果视图（PRD ORC-02）。
 *
 * @param taskId   任务 ID
 * @param pass     是否通过（{@code hasBlocking()==false}）
 * @param blocking 阻断项（有则禁止启动）
 * @param warnings 告警项
 */
public record PrecheckView(long taskId, boolean pass, List<String> blocking,
                           List<String> warnings) {

    /**
     * 从报告转换。
     *
     * @param report 预检报告
     * @return 视图
     */
    public static PrecheckView of(PrecheckReport report) {
        return new PrecheckView(report.getTaskId(), !report.hasBlocking(), report.getBlocking(),
                report.getWarnings());
    }

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", taskId);
        map.put("pass", pass);
        map.put("blocking", blocking);
        map.put("warnings", warnings);
        return map;
    }
}
