package com.dbsync.cdc;

import com.dbsync.common.enums.ConflictPolicy;
import com.dbsync.common.model.DeadLetter;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.util.RetryTemplate;
import com.dbsync.dialect.Dialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 增量消费的运行时上下文（一个任务一份，被该任务的所有 {@link GroupWorker} 共享）。
 *
 * <p>// ADDED: 04 文档未列出本类。{@code GroupWorker} 若把"分组/队列/水位/冲突策略/表元数据/
 * 连接提供者/限速与重试"全写成构造参数，会有 15+ 个参数且每加一个特性都要改所有调用点。
 * 收敛成只读上下文后，{@code GroupWorker} 只关心"组号"。
 *
 * <p>线程安全：所有字段不可变；{@code tables} 用 {@code Map.copyOf} 冻结。
 */
public final class CdcContext {

    /**
     * 连接提供者（生产为连接池借出，单测为假连接）。
     */
    @FunctionalInterface
    public interface ConnectionSupplier {

        /**
         * 借一个目标库连接。
         *
         * @return 连接
         * @throws SQLException 借出失败
         */
        Connection get() throws SQLException;
    }

    /**
     * 死信写入端口。
     *
     * <p>// ADDED: {@code DeadLetterService} 在 {@code db-sync-migration} 模块，
     * 而 cdc 不依赖 migration（依赖方向：migration ← cdc 会形成"存量依赖增量"的怪异结构）。
     * 用一个单方法端口解耦，由 api/server 层用 {@code deadLetterService::create} 接线。
     */
    @FunctionalInterface
    public interface DeadLetterWriter {

        /**
         * 写一条死信。
         *
         * @param letter 死信
         */
        void write(DeadLetter letter);
    }

    private final long taskId;
    private final int groupCount;
    private final int consumerConcurrency;
    private final String owner;
    private final ChangeQueue queue;
    private final WatermarkStore watermarks;
    private final ConflictResolver resolver;
    private final UpsertApplier applier;
    private final Dialect dialect;
    private final Map<Long, TableMeta> tables;
    private final ConflictPolicy policy;
    private final Map<Long, ConflictPolicy> policies;
    private final int batchSize;
    private final RetryTemplate retry;
    private final DeadLetterWriter deadLetters;
    private final ConnectionSupplier targetConnections;
    private final BooleanSupplier stopSignal;
    private final Duration groupLease;
    private final Duration pollInterval;

    /**
     * 构造（请用 {@link #builder()}）。
     *
     * @param builder 构建器
     */
    private CdcContext(Builder builder) {
        this.taskId = builder.taskId;
        this.groupCount = builder.groupCount <= 0 ? 16 : builder.groupCount;
        this.consumerConcurrency = Math.max(1, builder.consumerConcurrency);
        this.owner = builder.owner == null ? "cdc-local" : builder.owner;
        this.queue = builder.queue;
        this.watermarks = builder.watermarks;
        this.resolver = builder.resolver;
        this.applier = builder.applier == null ? new UpsertApplier() : builder.applier;
        this.dialect = builder.dialect;
        this.tables = Map.copyOf(builder.tables);
        this.policy = builder.policy == null ? ConflictPolicy.SOURCE_WINS : builder.policy;
        this.policies = builder.policies == null ? Map.of() : Map.copyOf(builder.policies);
        this.batchSize = builder.batchSize <= 0 ? 200 : builder.batchSize;
        this.retry = builder.retry == null ? new RetryTemplate(3, Duration.ofMillis(200L), 2.0d, 0.2d)
                : builder.retry;
        this.deadLetters = builder.deadLetters;
        this.targetConnections = builder.targetConnections;
        this.stopSignal = builder.stopSignal == null ? () -> false : builder.stopSignal;
        this.groupLease = builder.groupLease == null ? Duration.ofSeconds(30L) : builder.groupLease;
        this.pollInterval = builder.pollInterval == null ? Duration.ofMillis(200L)
                : builder.pollInterval;
    }

    /**
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /** @return 任务 ID */
    public long getTaskId() {
        return taskId;
    }

    /** @return 分组数 */
    public int getGroupCount() {
        return groupCount;
    }

    /** @return 消费并发度（= 同时处理的组数上限） */
    public int getConsumerConcurrency() {
        return consumerConcurrency;
    }

    /** @return 消费者实例 ID */
    public String getOwner() {
        return owner;
    }

    /** @return 变更队列 */
    public ChangeQueue getQueue() {
        return queue;
    }

    /** @return 水位仓 */
    public WatermarkStore getWatermarks() {
        return watermarks;
    }

    /** @return 冲突解决器 */
    public ConflictResolver getResolver() {
        return resolver;
    }

    /** @return 应用器 */
    public UpsertApplier getApplier() {
        return applier;
    }

    /** @return 目标方言 */
    public Dialect getDialect() {
        return dialect;
    }

    /** @return 冲突策略（<b>任务级兜底</b>；按表判定请用 {@link #policyOf(long)}） */
    public ConflictPolicy getPolicy() {
        return policy;
    }

    /**
     * 取某张<b>表</b>的冲突策略（CDC-06「三种策略按表可配」）。
     *
     * <p>优先级（写死在这里，只有一处真相）：
     * <ol>
     *   <li>表级：{@code sync_table_mapping.conflict_policy}（V3 迁移新增）；</li>
     *   <li>任务级：{@code sync_task.conflict_policy}（V1 已有）；</li>
     *   <li>{@code SOURCE_WINS}（{@link #getPolicy()} 的默认值）。</li>
     * </ol>
     *
     * <p>{@code GroupWorker} 必须在<b>每条</b>变更上调用本方法而不是缓存 {@link #getPolicy()}：
     * 同一个组会混合处理不同表的变更（分组键是主键哈希，不是表），
     * 用任务级策略统一处理会让"某张表配了 TARGET_WINS"静默失效。
     *
     * @param tableMappingId 表映射 ID
     * @return 该表的策略（永不为 null）
     */
    public ConflictPolicy policyOf(long tableMappingId) {
        ConflictPolicy tableLevel = policies.get(Long.valueOf(tableMappingId));
        return tableLevel == null ? policy : tableLevel;
    }

    /** @return 批大小 */
    public int getBatchSize() {
        return batchSize;
    }

    /** @return 重试模板 */
    public RetryTemplate getRetry() {
        return retry;
    }

    /** @return 死信写入端口 */
    public DeadLetterWriter getDeadLetters() {
        return deadLetters;
    }

    /** @return 目标连接提供者 */
    public ConnectionSupplier getTargetConnections() {
        return targetConnections;
    }

    /** @return 组租期 */
    public Duration getGroupLease() {
        return groupLease;
    }

    /** @return 空转轮询间隔 */
    public Duration getPollInterval() {
        return pollInterval;
    }

    /**
     * @return true 表示收到停止信号
     */
    public boolean stopRequested() {
        return stopSignal.getAsBoolean();
    }

    /**
     * 取表映射对应的目标表元数据。
     *
     * @param tableMappingId 表映射 ID
     * @return 表元数据；未配置返回 null
     */
    public TableMeta tableOf(long tableMappingId) {
        return tables.get(Long.valueOf(tableMappingId));
    }

    /**
     * 构建器（字段多，用构建器避免"位置参数错位"这类只有运行时才暴露的 bug）。
     */
    public static final class Builder {

        private long taskId;
        private int groupCount = 16;
        private int consumerConcurrency = 4;
        private String owner;
        private ChangeQueue queue;
        private WatermarkStore watermarks;
        private ConflictResolver resolver;
        private UpsertApplier applier;
        private Dialect dialect;
        private Map<Long, TableMeta> tables = new LinkedHashMap<>();
        private ConflictPolicy policy = ConflictPolicy.SOURCE_WINS;
        private Map<Long, ConflictPolicy> policies = new LinkedHashMap<>();
        private int batchSize = 200;
        private RetryTemplate retry;
        private DeadLetterWriter deadLetters;
        private ConnectionSupplier targetConnections;
        private BooleanSupplier stopSignal;
        private Duration groupLease = Duration.ofSeconds(30L);
        private Duration pollInterval = Duration.ofMillis(200L);

        /**
         * @param value 任务 ID
         * @return this
         */
        public Builder taskId(long value) {
            this.taskId = value;
            return this;
        }

        /**
         * @param value 分组数
         * @return this
         */
        public Builder groupCount(int value) {
            this.groupCount = value;
            return this;
        }

        /**
         * @param value 消费并发
         * @return this
         */
        public Builder consumerConcurrency(int value) {
            this.consumerConcurrency = value;
            return this;
        }

        /**
         * @param value 消费者 ID
         * @return this
         */
        public Builder owner(String value) {
            this.owner = value;
            return this;
        }

        /**
         * @param value 队列
         * @return this
         */
        public Builder queue(ChangeQueue value) {
            this.queue = value;
            return this;
        }

        /**
         * @param value 水位仓
         * @return this
         */
        public Builder watermarks(WatermarkStore value) {
            this.watermarks = value;
            return this;
        }

        /**
         * @param value 冲突解决器
         * @return this
         */
        public Builder resolver(ConflictResolver value) {
            this.resolver = value;
            return this;
        }

        /**
         * @param value 应用器
         * @return this
         */
        public Builder applier(UpsertApplier value) {
            this.applier = value;
            return this;
        }

        /**
         * @param value 目标方言
         * @return this
         */
        public Builder dialect(Dialect value) {
            this.dialect = value;
            return this;
        }

        /**
         * @param value 表映射 ID → 目标表元数据
         * @return this
         */
        public Builder tables(Map<Long, TableMeta> value) {
            this.tables = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
            return this;
        }

        /**
         * @param value 冲突策略（任务级兜底）
         * @return this
         */
        public Builder policy(ConflictPolicy value) {
            this.policy = value;
            return this;
        }

        /**
         * 表级冲突策略（CDC-06）。
         *
         * @param value 表映射 ID → 策略
         * @return this
         */
        public Builder policies(Map<Long, ConflictPolicy> value) {
            this.policies = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
            return this;
        }

        /**
         * @param value 批大小
         * @return this
         */
        public Builder batchSize(int value) {
            this.batchSize = value;
            return this;
        }

        /**
         * @param value 重试模板
         * @return this
         */
        public Builder retry(RetryTemplate value) {
            this.retry = value;
            return this;
        }

        /**
         * @param value 死信端口
         * @return this
         */
        public Builder deadLetters(DeadLetterWriter value) {
            this.deadLetters = value;
            return this;
        }

        /**
         * @param value 目标连接提供者
         * @return this
         */
        public Builder targetConnections(ConnectionSupplier value) {
            this.targetConnections = value;
            return this;
        }

        /**
         * @param value 停止信号
         * @return this
         */
        public Builder stopSignal(BooleanSupplier value) {
            this.stopSignal = value;
            return this;
        }

        /**
         * @param value 组租期
         * @return this
         */
        public Builder groupLease(Duration value) {
            this.groupLease = value;
            return this;
        }

        /**
         * @param value 轮询间隔
         * @return this
         */
        public Builder pollInterval(Duration value) {
            this.pollInterval = value;
            return this;
        }

        /**
         * @return 上下文
         */
        public CdcContext build() {
            if (queue == null || watermarks == null || resolver == null || dialect == null
                    || targetConnections == null) {
                throw new IllegalStateException("CdcContext 缺少必需组件（queue/watermarks/resolver/"
                        + "dialect/targetConnections）");
            }
            return new CdcContext(this);
        }
    }
}
