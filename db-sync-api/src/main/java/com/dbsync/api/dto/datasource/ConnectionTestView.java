package com.dbsync.api.dto.datasource;

import com.dbsync.core.datasource.ConnectionTestResult;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 连接测试结果视图（PRD DS-03）。
 *
 * @param success       是否成功
 * @param category      失败分类（AUTH/NETWORK/DRIVER/CONFIG/TIMEOUT/UNKNOWN）
 * @param latencyMs     往返耗时
 * @param serverVersion 服务端版本
 * @param errorCode     错误码
 * @param message       消息
 * @param hint          修复建议
 */
public record ConnectionTestView(boolean success, String category, long latencyMs,
                                 String serverVersion, String errorCode, String message,
                                 String hint) {

    /**
     * 从结果转换。
     *
     * @param result 连接测试结果
     * @return 视图
     */
    public static ConnectionTestView of(ConnectionTestResult result) {
        return new ConnectionTestView(result.isSuccess(),
                result.getCategory() == null ? null : result.getCategory().name(),
                result.getLatencyMs(), result.getServerVersion(), result.getErrorCode(),
                result.getMessage(), result.getHint());
    }

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("success", success);
        map.put("category", category);
        map.put("latencyMs", latencyMs);
        map.put("serverVersion", serverVersion);
        map.put("errorCode", errorCode);
        map.put("message", message);
        map.put("hint", hint);
        return map;
    }
}
