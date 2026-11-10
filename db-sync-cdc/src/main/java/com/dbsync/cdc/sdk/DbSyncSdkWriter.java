package com.dbsync.cdc.sdk;

import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.util.JsonUtil;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 带一致性模式与失败补偿的双写实现（PRD CDC-01）。
 *
 * <p>本类是<b>装饰器</b>：真正的"登记变更"逻辑仍在 {@link SingleNodeDbSyncWriter}
 * （或业务方自己的 {@link DbSyncWriter} 实现）里，这里只叠加三件事：
 * <ol>
 *   <li><b>可配重试</b>：默认 3 次，退避 50ms 起、×2、上限 500ms（同步等待，
 *       不引入额外线程——业务线程多等最多 &lt;1s，换取"多数抖动自愈"）；</li>
 *   <li><b>失败不阻断</b>（{@link SdkWriteMode#ASYNC}）：重试耗尽后落补偿表
 *       {@code sync_sdk_outbox} 并返回 {@link WriteOutcome#COMPENSATED}，
 *       <b>不抛异常</b>；连补偿都失败则记 {@link WriteOutcome#DROPPED} + 采样日志（最后一道防线）；</li>
 *   <li><b>严格失败</b>（{@link SdkWriteMode#STRICT}）：重试耗尽后抛 {@link SdkWriteException}，
 *       让业务方回滚自己的事务。</li>
 * </ol>
 *
 * <p><b>ASYNC 的例外（刻意保留，且必须写清）</b>：{@code 表名为空}、{@code 行数据为空}
 * 这类<b>调用方参数错误</b>在两种模式下都会抛 {@link DbSyncException}。
 * 理由：那不是"目标侧暂时不可用"，而是调用方传了脏数据；如果也吞掉并写进补偿表，
 * 重放器会把同一条非法数据反复重放，把一个编程错误变成长期噪音。
 *
 * <p><b>为什么补偿表放业务库</b>：见 {@link CompensationRecord} 类注释
 * （元数据库不可达时仍能留下痕迹）。
 *
 * <p><b>线程安全</b>：所有可变状态都是 {@link AtomicLong} 计数器；
 * 重放方法可与业务写入并发调用（补偿表的 PENDING→REPLAYED 迁移是带条件的 UPDATE，
 * 多实例并发重放同一行时只有一个能成功）。
 */
public class DbSyncSdkWriter implements DbSyncWriter {

    private static final Logger log = LoggerFactory.getLogger(DbSyncSdkWriter.class);

    /** 默认最大尝试次数（含首次）。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    /** 默认初始退避（毫秒）。 */
    public static final long DEFAULT_INITIAL_BACKOFF_MS = 50L;

    /** 默认退避上限（毫秒）。 */
    public static final long DEFAULT_MAX_BACKOFF_MS = 500L;

    /** 默认单次重放批量。 */
    public static final int DEFAULT_REPLAY_LIMIT = 100;

    /** 日志采样间隔：每 N 次失败打一条 error，避免"每条都打"把磁盘打满。 */
    private static final long LOG_SAMPLE_EVERY = 100L;

    /**
     * 退避休眠端口（测试注入"不真睡"的实现，生产用 {@link Thread#sleep}）。
     */
    public interface Sleeper {

        /**
         * 休眠。
         *
         * @param millis 毫秒
         * @throws InterruptedException 被中断
         */
        void sleep(long millis) throws InterruptedException;
    }

    private final DbSyncWriter delegate;
    private final CompensationStore compensationStore;
    private final long taskId;
    private final Map<String, List<String>> pkColumnsByTable;
    private final SdkWriteMode mode;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final Sleeper sleeper;
    private final Clock clock;

    private final AtomicLong appliedCount = new AtomicLong(0L);
    private final AtomicLong compensatedCount = new AtomicLong(0L);
    private final AtomicLong droppedCount = new AtomicLong(0L);
    private final AtomicLong flushFailureCount = new AtomicLong(0L);
    private final AtomicLong replaySuccessCount = new AtomicLong(0L);
    private final AtomicLong replayFailureCount = new AtomicLong(0L);

    /**
     * 构造（ASYNC 默认、无主键配置、默认重试与退避）。
     *
     * @param delegate           真正的写入器
     * @param compensationStore  补偿端口（ASYNC 下必填，STRICT 下可传 {@code null}）
     * @param taskId             同步任务 ID（补偿表 {@code task_id}）
     */
    public DbSyncSdkWriter(DbSyncWriter delegate, CompensationStore compensationStore,
                           long taskId) {
        this(delegate, compensationStore, taskId, Map.of(), SdkWriteMode.defaultMode(),
                DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_BACKOFF_MS, DEFAULT_MAX_BACKOFF_MS,
                millis -> Thread.sleep(millis), Clock.systemUTC());
    }

    /**
     * 构造（ASYNC 默认、指定主键配置）。
     *
     * @param delegate           真正的写入器
     * @param compensationStore  补偿端口
     * @param taskId             同步任务 ID
     * @param pkColumnsByTable   表名 → 主键列（大小写不敏感，用于异常与补偿记录里的主键）
     */
    public DbSyncSdkWriter(DbSyncWriter delegate, CompensationStore compensationStore, long taskId,
                           Map<String, List<String>> pkColumnsByTable) {
        this(delegate, compensationStore, taskId, pkColumnsByTable, SdkWriteMode.defaultMode(),
                DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_BACKOFF_MS, DEFAULT_MAX_BACKOFF_MS,
                millis -> Thread.sleep(millis), Clock.systemUTC());
    }

    /**
     * 构造（指定模式 + 主键配置，其余默认）。
     *
     * @param delegate           真正的写入器
     * @param compensationStore  补偿端口
     * @param taskId             同步任务 ID
     * @param pkColumnsByTable   表名 → 主键列
     * @param mode               一致性模式
     */
    public DbSyncSdkWriter(DbSyncWriter delegate, CompensationStore compensationStore, long taskId,
                           Map<String, List<String>> pkColumnsByTable, SdkWriteMode mode) {
        this(delegate, compensationStore, taskId, pkColumnsByTable, mode,
                DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_BACKOFF_MS, DEFAULT_MAX_BACKOFF_MS,
                millis -> Thread.sleep(millis), Clock.systemUTC());
    }

    /**
     * 构造（完整；测试可注入 {@link Sleeper} 与 {@link Clock}）。
     *
     * @param delegate          真正的写入器
     * @param compensationStore 补偿端口（可为 null，表示不留补偿痕迹）
     * @param taskId            同步任务 ID
     * @param pkColumnsByTable  表名 → 主键列（可 null）
     * @param mode              一致性模式（null → {@link SdkWriteMode#defaultMode()}）
     * @param maxAttempts       最大尝试次数（≤0 → 默认 3）
     * @param initialBackoffMs  初始退避（≤0 → 默认 50ms）
     * @param maxBackoffMs      退避上限（≤0 → 默认 500ms）
     * @param sleeper           休眠端口（null → {@link Thread#sleep}）
     * @param clock             时钟（null → 系统 UTC）
     */
    public DbSyncSdkWriter(DbSyncWriter delegate, CompensationStore compensationStore, long taskId,
                           Map<String, List<String>> pkColumnsByTable, SdkWriteMode mode,
                           int maxAttempts, long initialBackoffMs, long maxBackoffMs,
                           Sleeper sleeper, Clock clock) {
        if (delegate == null) {
            throw new IllegalArgumentException("写入器不能为空");
        }
        this.delegate = delegate;
        this.compensationStore = compensationStore;
        this.taskId = taskId;
        this.pkColumnsByTable = new LinkedHashMap<>();
        if (pkColumnsByTable != null) {
            for (Map.Entry<String, List<String>> entry : pkColumnsByTable.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                List<String> columns = entry.getValue() == null ? List.of()
                        : List.copyOf(entry.getValue());
                this.pkColumnsByTable.put(entry.getKey().toUpperCase(Locale.ROOT), columns);
            }
        }
        this.mode = mode == null ? SdkWriteMode.defaultMode() : mode;
        this.maxAttempts = maxAttempts <= 0 ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        this.initialBackoffMs = initialBackoffMs <= 0L ? DEFAULT_INITIAL_BACKOFF_MS
                : initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs <= 0L ? DEFAULT_MAX_BACKOFF_MS : maxBackoffMs;
        this.sleeper = sleeper == null ? millis -> Thread.sleep(millis) : sleeper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public long write(String table, Map<String, Object> row) {
        requireTable(table);
        if (row == null || row.isEmpty()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "双写行数据不能为空");
        }
        applyWrite(table, row);
        return 1L;
    }

    @Override
    public long delete(String table, List<Object> pkTuple) {
        requireTable(table);
        if (pkTuple == null || pkTuple.isEmpty()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "删除主键元组不能为空");
        }
        applyDelete(table, pkTuple);
        return 1L;
    }

    /**
     * 写一行并返回可判定结果（不存在 ASYNC 下"抛不抛"的歧义）。
     *
     * @param table 表名
     * @param row   行数据
     * @return 结果；STRICT 模式失败时<b>抛出</b> {@link SdkWriteException} 而不是返回
     */
    public WriteOutcome writeOutcome(String table, Map<String, Object> row) {
        requireTable(table);
        if (row == null || row.isEmpty()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "双写行数据不能为空");
        }
        return applyWrite(table, row);
    }

    /**
     * 删一行并返回可判定结果。
     *
     * @param table   表名
     * @param pkTuple 主键元组
     * @return 结果；STRICT 模式失败时抛出 {@link SdkWriteException}
     */
    public WriteOutcome deleteOutcome(String table, List<Object> pkTuple) {
        requireTable(table);
        if (pkTuple == null || pkTuple.isEmpty()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "删除主键元组不能为空");
        }
        return applyDelete(table, pkTuple);
    }

    @Override
    public void flush() {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                delegate.flush();
                return;
            } catch (RuntimeException e) {
                last = e;
                if (attempt < maxAttempts) {
                    sleepBackoff(attempt);
                }
            }
        }
        long failures = flushFailureCount.incrementAndGet();
        if (mode == SdkWriteMode.STRICT) {
            throw new SdkWriteException(taskId, "<flush>", List.of(), maxAttempts, last);
        }
        // ASYNC：刷失败时缓冲仍在 delegate 内（SingleNodeDbSyncWriter 会把批次放回），
        // 下一轮 flush 还会重试，因此这里只计数 + 采样告警，绝不打断业务。
        logSampledError("双写刷入失败（ASYNC 已忽略，缓冲区保留待下次刷）", failures, last);
    }

    @Override
    public boolean isHealthy() {
        try {
            return delegate.isHealthy();
        } catch (RuntimeException e) {
            // 健康检查本身就不该把业务打挂
            log.warn("双写健康检查失败，按不健康处理：{}", e.toString());
            return false;
        }
    }

    /**
     * 把补偿表里的 PENDING 记录重放到目标（运维/定时任务入口）。
     *
     * <p>语义：
     * <ol>
     *   <li>只取"已到重放时机"的记录（{@code next_retry_at <= now}）；</li>
     *   <li>成功的置 {@link CompensationStatus#REPLAYED}；</li>
     *   <li>失败的 {@code retry+1} 并按指数退避推后 {@code next_retry_at}
     *       （{@code initialBackoff * 2^retry}，上限 {@code maxBackoff * 10}），
     *       避免"目标库已经挂了还被高频重放"；</li>
     *   <li>单条失败不影响其它记录（逐条 try/catch）。</li>
     * </ol>
     *
     * <p><b>幂等性</b>：重放走的是与正常写入相同的 upsert 路径，
     * 同一变更重放多次，目标端仍然只有一行（由目标端 upsert 能力保证，见
     * {@code Dialect.buildUpsertSql}）。
     *
     * @param taskId 任务 ID
     * @param limit  单次上限（≤0 → {@value #DEFAULT_REPLAY_LIMIT}）
     * @return 成功重放的条数
     */
    public int replayPending(long taskId, int limit) {
        if (compensationStore == null) {
            log.warn("未配置补偿端口，replayPending 无记录可重放 task={}", taskId);
            return 0;
        }
        List<CompensationRecord> pendings = compensationStore.listPending(taskId, limit);
        if (pendings.isEmpty()) {
            return 0;
        }
        int replayed = 0;
        for (CompensationRecord record : pendings) {
            try {
                if (CompensationRecord.OP_DELETE.equals(record.getOp())) {
                    delegate.delete(record.getTableName(), record.pkTuple());
                } else {
                    delegate.write(record.getTableName(), payloadOf(record));
                }
                delegate.flush();
                compensationStore.markReplayed(record.getId());
                replaySuccessCount.incrementAndGet();
                replayed++;
            } catch (RuntimeException e) {
                replayFailureCount.incrementAndGet();
                Instant nextRetryAt = clock.instant()
                        .plusMillis(backoffForRetry(record.getRetry()));
                try {
                    compensationStore.markRetry(record.getId(), e.toString(), nextRetryAt);
                } catch (RuntimeException storeError) {
                    // 连"失败状态"都写不进去：只能留日志，下一轮还是同一条 PENDING
                    log.error("补偿记录重试状态写入失败 id={} 原因={}", Long.valueOf(record.getId()),
                            storeError.toString());
                }
                log.warn("补偿重放失败 id={} table={} retry={} 下次={} 原因={}",
                        Long.valueOf(record.getId()), record.getTableName(),
                        Integer.valueOf(record.getRetry()), nextRetryAt, e.toString());
            }
        }
        log.info("补偿重放完成 task={} 候选={} 成功={}", Long.valueOf(taskId),
                Integer.valueOf(pendings.size()), Integer.valueOf(replayed));
        return replayed;
    }

    /**
     * 单次写入（含重试、补偿、模式分支）。
     *
     * @param table 表名
     * @param row   行数据
     * @return 结果
     */
    private WriteOutcome applyWrite(String table, Map<String, Object> row) {
        List<Object> pkTuple = pkTupleOf(table, row);
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                delegate.write(table, row);
                appliedCount.incrementAndGet();
                return WriteOutcome.APPLIED;
            } catch (RuntimeException e) {
                last = e;
                if (attempt < maxAttempts) {
                    sleepBackoff(attempt);
                }
            }
        }
        return onExhausted(table, pkTuple, CompensationRecord.OP_UPDATE, JsonUtil.toJson(row), last);
    }

    /**
     * 单次删除（含重试、补偿、模式分支）。
     *
     * @param table   表名
     * @param pkTuple 主键元组
     * @return 结果
     */
    private WriteOutcome applyDelete(String table, List<Object> pkTuple) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                delegate.delete(table, pkTuple);
                appliedCount.incrementAndGet();
                return WriteOutcome.APPLIED;
            } catch (RuntimeException e) {
                last = e;
                if (attempt < maxAttempts) {
                    sleepBackoff(attempt);
                }
            }
        }
        return onExhausted(table, pkTuple, CompensationRecord.OP_DELETE, null, last);
    }

    /**
     * 重试耗尽后的分支：STRICT 抛异常；ASYNC 落补偿（补偿失败就只记日志）。
     *
     * @param table   表名
     * @param pkTuple 主键元组
     * @param op      操作码
     * @param payload 行数据 JSON（删除为 null）
     * @param cause   最后一次失败
     * @return 结果
     */
    private WriteOutcome onExhausted(String table, List<Object> pkTuple, String op, String payload,
                                     RuntimeException cause) {
        if (mode == SdkWriteMode.STRICT) {
            throw new SdkWriteException(taskId, table, pkTuple, maxAttempts, cause);
        }
        if (compensationStore == null) {
            long dropped = droppedCount.incrementAndGet();
            logSampledError("双写失败且未配置补偿端口（DROPPED）table=" + table, dropped, cause);
            return WriteOutcome.DROPPED;
        }
        try {
            Instant now = clock.instant();
            CompensationRecord record = CompensationRecord.pending(0L, taskId, table, op,
                    JsonUtil.toJson(pkTuple), payload, cause == null ? null : cause.toString(), now);
            compensationStore.record(record);
            long compensated = compensatedCount.incrementAndGet();
            logSampledError("双写失败已落补偿表（COMPENSATED）table=" + table, compensated, cause);
            return WriteOutcome.COMPENSATED;
        } catch (RuntimeException storeError) {
            // 最后一道防线：补偿也失败 → 只记日志 + 计数，仍然不抛给业务方
            long dropped = droppedCount.incrementAndGet();
            logSampledError("双写失败且补偿写入失败（DROPPED）table=" + table + " 补偿原因="
                    + storeError, dropped, cause);
            return WriteOutcome.DROPPED;
        }
    }

    /**
     * 第 n 次失败后的退避：{@code initial * 2^(n-1)}，上限 {@code maxBackoff}。
     *
     * @param attempt 已失败次数（1 起）
     */
    private void sleepBackoff(int attempt) {
        long millis = Math.min(maxBackoffMs, initialBackoffMs << Math.min(attempt - 1, 10));
        try {
            sleeper.sleep(Math.max(0L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 重放失败的退避：{@code initial * 2^retry}，上限放大到 {@code maxBackoff * 10}
     * （重放是后台任务，退避可以比同步路径更激进）。
     *
     * @param retry 已重试次数
     * @return 退避毫秒
     */
    private long backoffForRetry(int retry) {
        int shift = Math.min(Math.max(retry, 0), 10);
        return Math.min(maxBackoffMs * 10L, initialBackoffMs << shift);
    }

    /**
     * 从行数据里按配置解析主键元组（表未配置主键时返回空列表：不影响补偿重放，
     * 因为 U 类记录靠 {@code payload} 重放，主键只用于可读性与告警）。
     *
     * @param table 表名
     * @param row   行数据
     * @return 主键元组
     */
    private List<Object> pkTupleOf(String table, Map<String, Object> row) {
        List<String> pkColumns = pkColumnsByTable.get(table.toUpperCase(Locale.ROOT));
        if (pkColumns == null || pkColumns.isEmpty()) {
            return List.of();
        }
        List<Object> tuple = new ArrayList<>(pkColumns.size());
        for (String column : pkColumns) {
            tuple.add(lookup(row, column));
        }
        return tuple;
    }

    /**
     * 大小写不敏感取值（与 {@link SingleNodeDbSyncWriter} 同口径）。
     *
     * @param row    行数据
     * @param column 列名
     * @return 值
     */
    private static Object lookup(Map<String, Object> row, String column) {
        if (row.containsKey(column)) {
            return row.get(column);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(column)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 补偿记录 → 可重放的行数据。
     *
     * @param record 记录
     * @return 行数据（大小写不敏感 map）
     */
    private static Map<String, Object> payloadOf(CompensationRecord record) {
        Map<String, Object> payload = JsonUtil.parseObject(record.getPayload());
        if (payload.isEmpty()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID,
                    "补偿记录缺少 payload，无法重放 id=" + record.getId());
        }
        return payload;
    }

    /**
     * 表名非空校验。
     *
     * @param table 表名
     */
    private static void requireTable(String table) {
        if (table == null || table.isBlank()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "双写表名不能为空");
        }
    }

    /**
     * 采样日志：第 1 次必打，之后每 {@value #LOG_SAMPLE_EVERY} 次打一条 error。
     *
     * @param message  消息
     * @param count    累计次数
     * @param cause    根因（可 null）
     */
    private static void logSampledError(String message, long count, RuntimeException cause) {
        if (count == 1L || count % LOG_SAMPLE_EVERY == 0L) {
            if (cause == null) {
                log.error("{}（累计 {} 次）", message, Long.valueOf(count));
            } else {
                log.error("{}（累计 {} 次）", message, Long.valueOf(count), cause);
            }
        }
    }

    /** @return 同步成功次数 */
    public long appliedCount() {
        return appliedCount.get();
    }

    /** @return 落补偿次数 */
    public long compensatedCount() {
        return compensatedCount.get();
    }

    /** @return 彻底丢失次数（补偿也失败，必须告警） */
    public long droppedCount() {
        return droppedCount.get();
    }

    /** @return 刷入失败次数 */
    public long flushFailureCount() {
        return flushFailureCount.get();
    }

    /** @return 补偿重放成功次数 */
    public long replaySuccessCount() {
        return replaySuccessCount.get();
    }

    /** @return 补偿重放失败次数 */
    public long replayFailureCount() {
        return replayFailureCount.get();
    }

    /** @return 当前一致性模式 */
    public SdkWriteMode mode() {
        return mode;
    }

    /** @return 任务 ID */
    public long taskId() {
        return taskId;
    }
}
