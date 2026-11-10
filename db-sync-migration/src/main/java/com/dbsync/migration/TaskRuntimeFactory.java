package com.dbsync.migration;

import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.ReadOptions;
import com.dbsync.common.options.WriteOptions;
import com.dbsync.common.util.RetryTemplate;
import com.dbsync.core.checkpoint.CheckpointStore;
import com.dbsync.core.datasource.DataSourceHandle;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.core.read.JdbcReader;
import com.dbsync.core.throttle.Throttle;
import com.dbsync.core.throttle.ThrottleRegistry;
import com.dbsync.core.write.JdbcBatchWriter;
import com.dbsync.metadata.dao.ShardDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link TaskRuntime} 工厂：把"任务配置 + 元数据 + 数据源"装配成可执行上下文。
 *
 * <p>// ADDED: 04 文档未列出本类。之所以独立出来：装配过程要读任务、读表映射、
 * 读双侧表结构、建限速器/重试器，这 40 行逻辑若塞进 {@code MigrationEngine}，
 * Engine 会变成"状态机 + 装配 + 调度"三合一，既难读也难测。
 *
 * <p>本类<b>无状态</b>，可安全被多个任务并发调用。
 */
public class TaskRuntimeFactory {

    private static final Logger log = LoggerFactory.getLogger(TaskRuntimeFactory.class);

    private final SyncTaskDao syncTaskDao;
    private final TableMappingDao tableMappingDao;
    private final DataSourceRegistry registry;
    private final CheckpointStore checkpointStore;
    private final ShardDao shardDao;
    private final DeadLetterService deadLetterService;
    private final ThrottleRegistry throttleRegistry;

    /**
     * 构造。
     *
     * @param syncTaskDao      任务 DAO
     * @param tableMappingDao  表映射 DAO
     * @param registry         数据源注册表
     * @param checkpointStore  断点存储
     * @param shardDao         分片 DAO
     * @param deadLetterService 死信服务
     * @param throttleRegistry 限速器注册表（MIG-05：与 API 层共享同一实例的入口）
     */
    public TaskRuntimeFactory(SyncTaskDao syncTaskDao, TableMappingDao tableMappingDao,
                              DataSourceRegistry registry, CheckpointStore checkpointStore,
                              ShardDao shardDao, DeadLetterService deadLetterService,
                              ThrottleRegistry throttleRegistry) {
        this.syncTaskDao = syncTaskDao;
        this.tableMappingDao = tableMappingDao;
        this.registry = registry;
        this.checkpointStore = checkpointStore;
        this.shardDao = shardDao;
        this.deadLetterService = deadLetterService;
        this.throttleRegistry = throttleRegistry == null
                ? new ThrottleRegistry() : throttleRegistry;
    }

    /**
     * 装配运行时。
     *
     * @param taskId     任务 ID
     * @param stopSignal 暂停/取消信号
     * @return 运行时
     */
    public TaskRuntime create(long taskId, BooleanSupplier stopSignal) {
        SyncTask task = syncTaskDao.findById(taskId)
                .orElseThrow(() -> new DbSyncException(ErrorCode.TASK_NOT_FOUND,
                        "任务不存在：" + taskId));
        List<TableMapping> mappings = tableMappingDao.findEnabledByTask(taskId);
        if (mappings.isEmpty()) {
            throw new DbSyncException(ErrorCode.PRECHECK_FAILED,
                    "任务 " + taskId + " 没有启用的表映射，无法启动迁移");
        }
        DataSourceHandle source = registry.handle(task.getSourceDsId());
        DataSourceHandle target = registry.handle(task.getTargetDsId());

        Map<Long, ShardContext> contexts = new LinkedHashMap<>();
        for (TableMapping mapping : mappings) {
            TableMeta sourceTable = loadTable(source, mapping.getSourceSchema(),
                    mapping.getSourceTable(), "源");
            TableMeta targetTable = loadTable(target, mapping.getTargetSchema(),
                    mapping.getTargetTable(), "目标");
            ReadOptions readOptions = sourceTable.hasLobColumn()
                    ? ReadOptions.lobSafe() : ReadOptions.of(task.getBatchSize());
            ShardContext ctx = new ShardContext(taskId, mapping, sourceTable, targetTable,
                    source.dialect(), target.dialect(), source::connection, target::connection,
                    readOptions, WriteOptions.upsert(), stopSignal);
            contexts.put(mapping.getId(), ctx);
        }

        // 限速器必须来自注册表：API 层运行中改限速时，就是拿这里登记的那个实例做热更新。
        // 直接 new 会让"改限速"改到一个没人使用的对象上（改了等于没改）。
        Throttle throttle = throttleRegistry.acquire(taskId, task.getRateLimitRows(),
                task.getRateLimitRows());
        RetryTemplate retry = new RetryTemplate(Math.max(1, task.getMaxRetry()),
                Duration.ofMillis(500L), 2.0d, 0.2d);

        log.info("装配任务运行时 task={} tables={} batch={} concurrency={} rateLimit={}", taskId,
                contexts.size(), task.getBatchSize(), task.getConcurrency(),
                task.getRateLimitRows());
        return new TaskRuntime(task, contexts, new JdbcReader(), new JdbcBatchWriter(),
                checkpointStore, shardDao, deadLetterService, throttle, retry);
    }

    /**
     * 读表元数据（缺失时给出可执行提示，而不是裸 NPE）。
     *
     * @param handle 数据源句柄
     * @param schema schema（可空）
     * @param table  表名
     * @param side   侧别
     * @return 表元数据
     */
    private TableMeta loadTable(DataSourceHandle handle, String schema, String table, String side) {
        TableMeta meta;
        try {
            meta = handle.withConnection(connection ->
                    handle.dialect().metadata().loadTable(connection, schema, table));
        } catch (RuntimeException e) {
            throw new DbSyncException(ErrorCode.PRECHECK_FAILED, e,
                    side + "表读取失败：" + (schema == null ? "" : schema + ".") + table
                            + "（" + e.getMessage() + "）");
        }
        if (meta == null) {
            throw new DbSyncException(ErrorCode.PRECHECK_FAILED,
                    side + "表不存在：" + (schema == null ? "" : schema + ".") + table);
        }
        return meta;
    }
}
