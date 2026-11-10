package com.dbsync.migration;

import com.dbsync.common.enums.ShardState;
import com.dbsync.common.enums.TaskState;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 迁移进度视图（只读快照，供 API/大盘展示）。
 *
 * <p>{@code percent} 的算法刻意<b>不用</b> "已提交行数 / 估算总行数"：
 * 估算行数来自统计信息，误差可能 30% 以上，会让进度条来回跳。
 * 这里用"已成功分片数 + 在跑分片的行数进度"，进度条单调递增。
 */
public final class MigrationProgress {

    private final long taskId;
    private final TaskState taskState;
    private final int totalShards;
    private final Map<ShardState, Integer> shardCounts;
    private final long committedRows;
    private final long estimatedRows;

    /**
     * 构造。
     *
     * @param taskId        任务 ID
     * @param taskState     任务状态
     * @param totalShards   分片总数
     * @param shardCounts   各状态分片数
     * @param committedRows 已提交行数
     * @param estimatedRows 估算总行数
     */
    public MigrationProgress(long taskId, TaskState taskState, int totalShards,
                             Map<ShardState, Integer> shardCounts, long committedRows,
                             long estimatedRows) {
        this.taskId = taskId;
        this.taskState = taskState;
        this.totalShards = totalShards;
        this.shardCounts = new EnumMap<ShardState, Integer>(ShardState.class);
        if (shardCounts != null) {
            this.shardCounts.putAll(shardCounts);
        }
        this.committedRows = committedRows;
        this.estimatedRows = estimatedRows;
    }

    /** @return 任务 ID */
    public long getTaskId() {
        return taskId;
    }

    /** @return 任务状态 */
    public TaskState getTaskState() {
        return taskState;
    }

    /** @return 分片总数 */
    public int getTotalShards() {
        return totalShards;
    }

    /** @return 各状态分片数（不可变） */
    public Map<ShardState, Integer> getShardCounts() {
        return Collections.unmodifiableMap(shardCounts);
    }

    /** @return 已提交行数 */
    public long getCommittedRows() {
        return committedRows;
    }

    /** @return 估算总行数（≤0 表示未知） */
    public long getEstimatedRows() {
        return estimatedRows;
    }

    /** @return 已成功分片数 */
    public int getSuccessShards() {
        Integer v = shardCounts.get(ShardState.SUCCESS);
        return v == null ? 0 : v.intValue();
    }

    /** @return 失败/死信分片数 */
    public int getFailedShards() {
        Integer failed = shardCounts.get(ShardState.FAILED);
        Integer dead = shardCounts.get(ShardState.DEAD);
        return (failed == null ? 0 : failed.intValue()) + (dead == null ? 0 : dead.intValue());
    }

    /**
     * 完成百分比（0..100，单调来源：分片完成度）。
     *
     * @return 百分比
     */
    public double percent() {
        if (totalShards <= 0) {
            return 0d;
        }
        return Math.min(100d, getSuccessShards() * 100d / totalShards);
    }

    /**
     * 是否全部完成（成功分片数 == 总数）。
     *
     * @return true 表示完成
     */
    public boolean isFinished() {
        return totalShards > 0 && getSuccessShards() == totalShards;
    }

    @Override
    public String toString() {
        return "MigrationProgress[task=" + taskId + "," + getSuccessShards() + "/" + totalShards
                + ",rows=" + committedRows + "," + String.format(java.util.Locale.ROOT, "%.1f",
                Double.valueOf(percent())) + "%]";
    }
}
