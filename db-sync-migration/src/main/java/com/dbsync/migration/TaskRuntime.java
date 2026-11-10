package com.dbsync.migration;

import com.dbsync.common.enums.ShardState;
import com.dbsync.common.model.PkRange;
import com.dbsync.common.model.Shard;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.options.ShardPlanOptions;
import com.dbsync.common.util.RetryTemplate;
import com.dbsync.common.util.SnowflakeId;
import com.dbsync.core.checkpoint.CheckpointStore;
import com.dbsync.core.read.JdbcReader;
import com.dbsync.core.shard.ShardPlan;
import com.dbsync.core.shard.ShardPlanner;
import com.dbsync.core.throttle.Throttle;
import com.dbsync.core.write.JdbcBatchWriter;
import com.dbsync.metadata.dao.ShardDao;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一个任务的运行时上下文（表映射 → 分片执行上下文 + 共享组件）。
 *
 * <p>// ADDED: 04 文档未列出本类，但 {@code MigrationEngine}/{@code ShardDispatcher}
 * 需要一个"任务级"对象来承载：多张表映射各自的双侧连接与元数据、
 * 共享的读取器/写入器/断点存储/限速/重试。没有它，Engine 与 Dispatcher
 * 之间只能靠十几个参数传递，且分片规划逻辑无处安放。
 *
 * <p>生命周期：{@code start}/{@code resume}/{@code recover} 时由
 * {@link TaskRuntimeFactory} 构造，`Dispatcher` 用完即弃；
 * 连接池归 {@code DataSourceRegistry} 所有，本类<b>不</b>关闭数据源。
 */
public final class TaskRuntime {

    private final SyncTask task;
    private final Map<Long, ShardContext> contexts;
    private final JdbcReader reader;
    private final JdbcBatchWriter writer;
    private final CheckpointStore checkpointStore;
    private final ShardDao shardDao;
    private final DeadLetterService deadLetterService;
    private final Throttle throttle;
    private final RetryTemplate retry;
    private final SnowflakeId idGenerator;

    /**
     * 构造。
     *
     * @param task              任务
     * @param contexts          表映射 ID → 执行上下文（保持插入顺序，便于复现）
     * @param reader            流式读取器
     * @param writer            批量写入器
     * @param checkpointStore   断点存储
     * @param shardDao          分片 DAO
     * @param deadLetterService 死信服务
     * @param throttle          限速器
     * @param retry             重试模板
     */
    public TaskRuntime(SyncTask task, Map<Long, ShardContext> contexts, JdbcReader reader,
                       JdbcBatchWriter writer, CheckpointStore checkpointStore, ShardDao shardDao,
                       DeadLetterService deadLetterService, Throttle throttle,
                       RetryTemplate retry) {
        this.task = task;
        this.contexts = new LinkedHashMap<>(contexts);
        this.reader = reader;
        this.writer = writer;
        this.checkpointStore = checkpointStore;
        this.shardDao = shardDao;
        this.deadLetterService = deadLetterService;
        this.throttle = throttle == null ? Throttle.unlimited() : throttle;
        this.retry = retry == null ? RetryTemplate.defaults() : retry;
        this.idGenerator = new SnowflakeId(SnowflakeId.defaultWorkerId());
    }

    /** @return 任务 */
    public SyncTask getTask() {
        return task;
    }

    /** @return 任务 ID */
    public long getTaskId() {
        return task.getId();
    }

    /** @return 表映射 ID → 上下文（只读视图） */
    public Map<Long, ShardContext> getContexts() {
        return Map.copyOf(contexts);
    }

    /** @return 表映射数量 */
    public int tableCount() {
        return contexts.size();
    }

    /**
     * 取某张表映射的上下文。
     *
     * @param tableMappingId 表映射 ID
     * @return 上下文
     */
    public Optional<ShardContext> contextOf(long tableMappingId) {
        return Optional.ofNullable(contexts.get(tableMappingId));
    }

    /**
     * 为分片创建执行器（每个分片一个实例：无共享可变状态，天然线程安全）。
     *
     * @param shard 分片
     * @return 执行器
     */
    public ShardWorker newWorker(Shard shard) {
        ShardContext ctx = contexts.get(shard.getTableMappingId());
        if (ctx == null) {
            throw new IllegalStateException("分片引用了不存在的表映射：" + shard.getTableMappingId()
                    + "（taskId=" + task.getId() + "）");
        }
        return new ShardWorker(ctx, reader, writer, checkpointStore, shardDao, deadLetterService,
                throttle, retry);
    }

    /**
     * 规划分片（只规划，不落库）。
     *
     * <p>每个表映射单独规划：切分点必须落在<b>该表</b>的主键分布上，
     * 不同表的自增区间差异极大，混在一起规划会出现"一张表 1 个分片、另一张 1000 个"。
     *
     * @param planner 分片规划器
     * @param options 规划选项（null 时按任务配置推导）
     * @return 分片列表（ID 已分配、状态 PENDING）
     */
    public List<Shard> planShards(ShardPlanner planner, ShardPlanOptions options) {
        ShardPlanOptions opts = options == null
                ? new ShardPlanOptions(task.getShardRows(), 1, 1024, 8) : options;
        List<Shard> shards = new ArrayList<>();
        int shardNo = 0;
        for (Map.Entry<Long, ShardContext> entry : contexts.entrySet()) {
            long mappingId = entry.getKey();
            ShardContext ctx = entry.getValue();
            long estimatedRows = ctx.getSourceTable().getEstimatedRows();
            ShardPlan plan = planner.plan(ctx.getSourceTable(), estimatedRows, opts);
            List<PkRange> ranges = plan.getRanges();
            if (ranges.isEmpty()) {
                ranges = List.of(PkRange.unbounded(plan.getRangeKind()));
            }
            for (PkRange range : ranges) {
                shards.add(new Shard(idGenerator.nextId(), task.getId(), mappingId, shardNo++,
                        plan.getRangeKind(), asString(range.getStart()), asString(range.getEnd()),
                        ShardState.PENDING, null, null, 0L, 0, 0));
            }
        }
        return shards;
    }

    /**
     * 区间端点转字符串（{@code sync_shard} 统一用字符串承载，避免数值/字符两套列）。
     *
     * @param value 端点
     * @return 字符串端点（null 表示无界）
     */
    static String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    /**
     * 表映射列表（诊断/进度估算用）。
     *
     * @return 表映射
     */
    public List<TableMapping> mappings() {
        List<TableMapping> list = new ArrayList<>();
        for (ShardContext ctx : contexts.values()) {
            list.add(ctx.getMapping());
        }
        return list;
    }

    @Override
    public String toString() {
        return "TaskRuntime[task=" + task.getId() + ",tables=" + contexts.size() + ",batch="
                + task.getBatchSize() + ",concurrency=" + task.getConcurrency() + "]";
    }
}
