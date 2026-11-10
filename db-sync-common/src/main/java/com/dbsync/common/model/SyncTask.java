package com.dbsync.common.model;

import com.dbsync.common.enums.ConflictPolicy;
import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.enums.TaskState;
import java.time.Instant;

/**
 * 同步任务（可变对象：状态由引擎推进）。
 *
 * <p>{@code groupCount} 创建后只读 —— 改变分组会改变已有变更的归属，导致保序被破坏（架构 §4.3.2）。
 *
 * <p><b>任务级增量配置的两个字段</b>（V1 建表时就有列，但此前模型层没读出来）：
 * <ul>
 *   <li>{@link #getPollIntervalMs()} ← {@code sync_task.poll_interval_ms}：
 *       采集轮询周期，<b>优先于</b>全局默认（{@code dbsync.cdc.poll-interval-ms}）；</li>
 *   <li>{@link #getConflictPolicy()} ← {@code sync_task.conflict_policy}：
 *       冲突策略的<b>任务级兜底</b>，表级（{@code sync_table_mapping.conflict_policy}）优先于它。</li>
 * </ul>
 * 两者用 setter 写入（与 {@code lastError/startedAt} 同一手法），而不是加到构造函数：
 * 构造参数已有 13 个，再加会让所有调用点（含 30+ 处测试）跟着改，而这两个值只在
 * DAO 读库时需要——那正是 setter 的用途。
 */
public final class SyncTask {

    private final long id;
    private final String name;
    private final long sourceDsId;
    private final long targetDsId;
    private final int groupCount;
    private final int concurrency;
    private final int batchSize;
    private final int shardRows;
    private final long rateLimitRows;
    private final int maxRetry;
    private final IncrementalMode incrementalMode;
    private final int consumerConcurrency;
    private TaskState state;
    private String lastError;
    private Instant startedAt;
    private Instant finishedAt;

    /** 采集轮询周期（毫秒）；{@code <=0} 表示"未配置，回落到全局默认"。 */
    private int pollIntervalMs;

    /** 任务级冲突策略（表级优先于它；null 表示未配置，回落到 {@code SOURCE_WINS}）。 */
    private ConflictPolicy conflictPolicy;

    /**
     * 构造。
     *
     * @param id                  任务 ID
     * @param name                名称
     * @param sourceDsId          源数据源 ID
     * @param targetDsId          目标数据源 ID
     * @param groupCount          分组数（只读）
     * @param concurrency         迁移并发
     * @param batchSize           批大小
     * @param shardRows           每分片目标行数
     * @param rateLimitRows       读限速（0=不限）
     * @param maxRetry            最大重试次数
     * @param incrementalMode     增量模式
     * @param consumerConcurrency 增量消费并发
     * @param state               状态
     */
    public SyncTask(long id, String name, long sourceDsId, long targetDsId, int groupCount,
                    int concurrency, int batchSize, int shardRows, long rateLimitRows, int maxRetry,
                    IncrementalMode incrementalMode, int consumerConcurrency, TaskState state) {
        this.id = id;
        this.name = name;
        this.sourceDsId = sourceDsId;
        this.targetDsId = targetDsId;
        this.groupCount = groupCount <= 0 ? 16 : groupCount;
        this.concurrency = concurrency <= 0 ? 4 : concurrency;
        this.batchSize = batchSize <= 0 ? 1000 : batchSize;
        this.shardRows = shardRows <= 0 ? 2_000_000 : shardRows;
        this.rateLimitRows = Math.max(0, rateLimitRows);
        this.maxRetry = maxRetry <= 0 ? 3 : maxRetry;
        this.incrementalMode = incrementalMode == null ? IncrementalMode.TRIGGER_LOG : incrementalMode;
        this.consumerConcurrency = consumerConcurrency <= 0 ? 8 : consumerConcurrency;
        this.state = state == null ? TaskState.DRAFT : state;
    }

    /** @return 任务 ID */
    public long getId() {
        return id;
    }

    /** @return 任务名 */
    public String getName() {
        return name;
    }

    /** @return 源数据源 ID */
    public long getSourceDsId() {
        return sourceDsId;
    }

    /** @return 目标数据源 ID */
    public long getTargetDsId() {
        return targetDsId;
    }

    /** @return 分组数（只读） */
    public int getGroupCount() {
        return groupCount;
    }

    /** @return 迁移并发 */
    public int getConcurrency() {
        return concurrency;
    }

    /** @return 批大小 */
    public int getBatchSize() {
        return batchSize;
    }

    /** @return 每分片目标行数 */
    public int getShardRows() {
        return shardRows;
    }

    /** @return 读限速（行/秒） */
    public long getRateLimitRows() {
        return rateLimitRows;
    }

    /** @return 最大重试 */
    public int getMaxRetry() {
        return maxRetry;
    }

    /** @return 增量模式 */
    public IncrementalMode getIncrementalMode() {
        return incrementalMode;
    }

    /** @return 增量消费并发 */
    public int getConsumerConcurrency() {
        return consumerConcurrency;
    }

    /** @return 当前状态 */
    public TaskState getState() {
        return state;
    }

    /**
     * 设置状态。
     *
     * @param state 新状态
     */
    public void setState(TaskState state) {
        this.state = state;
    }

    /** @return 最后错误 */
    public String getLastError() {
        return lastError;
    }

    /**
     * 设置最后错误。
     *
     * @param lastError 错误信息
     */
    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    /** @return 启动时间 */
    public Instant getStartedAt() {
        return startedAt;
    }

    /**
     * 设置启动时间。
     *
     * @param startedAt 时间
     */
    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    /** @return 完成时间 */
    public Instant getFinishedAt() {
        return finishedAt;
    }

    /**
     * 设置完成时间。
     *
     * @param finishedAt 时间
     */
    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    /**
     * 采集轮询周期（毫秒）。
     *
     * <p>来源 {@code sync_task.poll_interval_ms}（V1 默认 5000）。
     *
     * @return 周期毫秒；{@code <=0} 表示未配置，调用方应回落到全局默认
     */
    public int getPollIntervalMs() {
        return pollIntervalMs;
    }

    /**
     * 设置采集轮询周期。
     *
     * @param pollIntervalMs 周期毫秒（{@code <=0} 表示未配置）
     */
    public void setPollIntervalMs(int pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * 任务级冲突策略。
     *
     * <p>来源 {@code sync_task.conflict_policy}（V1 默认 {@code SOURCE_WINS}）。
     * <b>优先级</b>：表级 {@code sync_table_mapping.conflict_policy}
     * → 本字段 → {@code SOURCE_WINS}。
     *
     * @return 策略；未配置返回 null（调用方回落 {@code SOURCE_WINS}）
     */
    public ConflictPolicy getConflictPolicy() {
        return conflictPolicy;
    }

    /**
     * 设置任务级冲突策略。
     *
     * @param conflictPolicy 策略（null 表示未配置）
     */
    public void setConflictPolicy(ConflictPolicy conflictPolicy) {
        this.conflictPolicy = conflictPolicy;
    }

    @Override
    public String toString() {
        return "SyncTask#" + id + "(" + name + "," + state + ")";
    }
}
