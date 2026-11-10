package com.dbsync.api.dto.migration;

import com.dbsync.common.model.DeadLetter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 死信视图（T27 / T45）。
 *
 * @param id              死信 ID
 * @param taskId          任务 ID
 * @param tableMappingId  表映射 ID
 * @param sourceType      来源（SHARD/CDC）
 * @param errorCode       错误码
 * @param errorMsg        错误消息
 * @param status          状态
 * @param retry           已重试次数
 * @param pkJson          主键 JSON（已脱敏）
 * @param payloadJson     载荷 JSON（仅 CDC）
 * @param replayBy        重放人
 */
public record DeadLetterView(long id, long taskId, Long tableMappingId, String sourceType,
                            String errorCode, String errorMsg, String status, int retry,
                            String pkJson, String payloadJson, String replayBy) {

    /**
     * 从模型转换。
     *
     * @param letter 死信
     * @return 视图
     */
    public static DeadLetterView of(DeadLetter letter) {
        return new DeadLetterView(letter.getId(), letter.getTaskId(), letter.getTableMappingId(),
                letter.getSourceType() == null ? null : letter.getSourceType().name(),
                letter.getErrorCode(), letter.getErrorMsg(),
                letter.getStatus() == null ? null : letter.getStatus().name(), letter.getRetry(),
                letter.getPkJson(), letter.getPayloadJson(), null);
    }

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("taskId", taskId);
        map.put("tableMappingId", tableMappingId);
        map.put("sourceType", sourceType);
        map.put("errorCode", errorCode);
        map.put("errorMsg", errorMsg);
        map.put("status", status);
        map.put("retry", retry);
        map.put("pkJson", pkJson);
        map.put("replayBy", replayBy);
        return map;
    }
}
