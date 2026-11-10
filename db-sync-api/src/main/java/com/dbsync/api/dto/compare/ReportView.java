package com.dbsync.api.dto.compare;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 报告视图（T37：{@code /report} 与 {@code /report/export}）。
 *
 * @param jobId    作业 ID
 * @param htmlPath HTML 落盘路径
 * @param csvPath  CSV 落盘路径
 * @param html     HTML 内容（导出接口不下发内容，避免响应体过大）
 * @param available 报告是否已生成
 */
public record ReportView(long jobId, String htmlPath, String csvPath, String html,
                         boolean available) {

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jobId", jobId);
        map.put("htmlPath", htmlPath);
        map.put("csvPath", csvPath);
        map.put("html", html);
        map.put("available", available);
        return map;
    }
}
