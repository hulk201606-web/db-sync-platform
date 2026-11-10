package com.dbsync.api.dto.task;

import com.dbsync.common.model.SyncTask;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务视图。
 *
 * @param id                  任务 ID
 * @param name                名称
 * @param sourceDsId          源数据源
 * @param targetDsId          目标数据源
 * @param state               状态
 * @param groupCount          分组数
 * @param concurrency         迁移并发
 * @param batchSize           批大小
 * @param shardRows           每分片行数
 * @param rateLimitRows       限速
 * @param maxRetry            最大重试
 * @param incrementalMode     增量模式
 * @param consumerConcurrency 消费并发
 * @param lastError           最近错误
 */
public record TaskView(long id, String name, long sourceDsId, long targetDsId, String state,
                       int groupCount, int concurrency, int batchSize, long shardRows,
                       long rateLimitRows, int maxRetry, String incrementalMode,
                       int consumerConcurrency, String lastError) {

    /**
     * 从模型转换。
     *
     * @param task 任务
     * @return 视图
     */
    public static TaskView of(SyncTask task) {
        return new TaskView(task.getId(), task.getName(), task.getSourceDsId(),
                task.getTargetDsId(), task.getState().name(), task.getGroupCount(),
                task.getConcurrency(), task.getBatchSize(), task.getShardRows(),
                task.getRateLimitRows(), task.getMaxRetry(), task.getIncrementalMode().name(),
                task.getConsumerConcurrency(), task.getLastError());
    }

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("sourceDsId", sourceDsId);
        map.put("targetDsId", targetDsId);
        map.put("state", state);
        map.put("groupCount", groupCount);
        map.put("concurrency", concurrency);
        map.put("batchSize", batchSize);
        map.put("shardRows", shardRows);
        map.put("rateLimitRows", rateLimitRows);
        map.put("maxRetry", maxRetry);
        map.put("incrementalMode", incrementalMode);
        map.put("consumerConcurrency", consumerConcurrency);
        map.put("lastError", lastError);
        return map;
    }
}
