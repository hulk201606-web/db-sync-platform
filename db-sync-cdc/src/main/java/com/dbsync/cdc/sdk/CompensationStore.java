package com.dbsync.cdc.sdk;

import java.time.Instant;
import java.util.List;

/**
 * 补偿端口（PRD CDC-01 的"失败不阻断 + 可重放"能力）。
 *
 * <p><b>为什么是端口而不是直接 JDBC</b>：补偿的落地位置取决于业务方的数据库与运维规范
 * （业务库本地表 / 独立运维库 / 甚至只打日志由外部采集）。SDK 只约定"能记、能查、能置状态"，
 * 具体实现由业务方选择：
 * <ul>
 *   <li>{@link JdbcCompensationStore}：默认实现，落<b>业务侧数据库</b>的 {@code sync_sdk_outbox}
 *       （见 {@link CompensationRecord} 类注释的取舍说明）；</li>
 *   <li>{@link NoopCompensationStore}：不留补偿痕迹（业务方自己有可靠队列时用）；</li>
 *   <li>业务方自定义实现（例如写 Kafka / Redis Stream）。</li>
 * </ul>
 *
 * <p><b>实现约定（对 ASYNC 模式至关重要）</b>：本端口的方法<b>可以抛运行时异常</b>，
 * 由 {@link DbSyncSdkWriter} 统一兜住。也就是说实现方不需要自己吞异常，
 * 但也不要把异常包装成"看起来成功"——那会让 {@link WriteOutcome#COMPENSATED}
 * 与 {@link WriteOutcome#DROPPED} 失去区分度。
 */
public interface CompensationStore {

    /**
     * 记一条待重放记录。
     *
     * @param record 记录（status 应为 {@link CompensationStatus#PENDING}）
     * @return 落库后的主键
     */
    long record(CompensationRecord record);

    /**
     * 查一批待重放记录（按 {@code next_retry_at, created_at} 升序，只返回已到重放时机的）。
     *
     * @param taskId 任务 ID
     * @param limit  上限（≤0 时按 100 处理）
     * @return 记录列表
     */
    List<CompensationRecord> listPending(long taskId, int limit);

    /**
     * 标记为已重放。
     *
     * @param id 记录 ID
     * @return true 表示确实从 PENDING 迁移到 REPLAYED
     */
    boolean markReplayed(long id);

    /**
     * 标记为已丢弃（人工判定无需重放）。
     *
     * @param id     记录 ID
     * @param reason 理由（落 {@code last_error}，便于事后追责）
     * @return true 表示确实从 PENDING 迁移到 DISCARDED
     */
    boolean markDiscarded(long id, String reason);

    /**
     * 重放失败：重试次数 +1，并推后下次重放时间（指数退避）。
     *
     * <p>该方法<b>不在</b>任务书列的四个方法里，但"失败累加 retry 并推 next_retry_at"
     * 必须由端口承担——否则重放器要么自己写 SQL（破坏方言隔离），要么无限快重试
     * （打挂目标库）。
     *
     * @param id          记录 ID
     * @param lastError   本次失败原因
     * @param nextRetryAt 下次可重放时间
     * @return true 表示更新成功（记录仍是 PENDING）
     */
    boolean markRetry(long id, String lastError, Instant nextRetryAt);
}
