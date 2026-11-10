package com.dbsync.api.dto.migration;

import com.dbsync.common.enums.ShardState;
import com.dbsync.migration.MigrationProgress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 迁移进度视图（PRD ORC-04 / T27）。
 *
 * @param taskId        任务 ID
 * @param taskState     任务状态
 * @param totalShards   分片总数
 * @param shardCounts   各状态分片数
 * @param committedRows 已提交行数
 * @param estimatedRows 估算总行数
 * @param percent       完成百分比（0~100，保留 2 位）
 * @param successShards 成功分片数
 * @param failedShards  失败分片数
 * @param finished      是否结束
 */
public record MigrationProgressView(long taskId, String taskState, int totalShards,
                                    Map<String, Integer> shardCounts, long committedRows,
                                    long estimatedRows, double percent, int successShards,
                                    int failedShards, boolean finished) {

    /**
     * 从模型转换。
     *
     * @param progress 进度
     * @return 视图
     */
    public static MigrationProgressView of(MigrationProgress progress) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Map.Entry<ShardState, Integer> entry : progress.getShardCounts().entrySet()) {
            counts.put(entry.getKey().name(), entry.getValue());
        }
        return new MigrationProgressView(progress.getTaskId(), progress.getTaskState().name(),
                progress.getTotalShards(), counts, progress.getCommittedRows(),
                progress.getEstimatedRows(), progress.percent(), progress.getSuccessShards(),
                progress.getFailedShards(), progress.isFinished());
    }

    /**
     * @return 有序 Map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", taskId);
        map.put("taskState", taskState);
        map.put("totalShards", totalShards);
        map.put("shardCounts", shardCounts);
        map.put("committedRows", committedRows);
        map.put("estimatedRows", estimatedRows);
        map.put("percent", percent);
        map.put("successShards", successShards);
        map.put("failedShards", failedShards);
        map.put("finished", finished);
        return map;
    }
}
