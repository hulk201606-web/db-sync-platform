package com.dbsync.api.cdc;

import com.dbsync.cdc.CdcContext;
import com.dbsync.cdc.ChangeConsumer;
import com.dbsync.cdc.ChangeQueue;
import com.dbsync.cdc.ConflictResolver;
import com.dbsync.cdc.UpsertApplier;
import com.dbsync.cdc.WatermarkStore;
import com.dbsync.common.enums.ConflictPolicy;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.util.RetryTemplate;
import com.dbsync.core.datasource.DataSourceHandle;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.dialect.Dialect;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ChangeConsumer.ContextFactory} 的唯一生产实现：按任务把 {@link CdcContext} 的 18 个字段装配齐。
 *
 * <p><b>为什么放在 api 模块而不是 cdc 模块</b>：
 * 装配要读 {@code sync_task} / {@code sync_table_mapping}、要借目标库连接池、要写死信表，
 * 这些依赖（metadata / core / migration）都比 cdc 更"外围"。若塞进 cdc，就变成
 * "增量依赖存量/元数据"，与架构的分层方向相反（cdc 只依赖 common/dialect/metadata 的 DAO 契约）。
 *
 * <p><b>为什么每次 start 都重新装配</b>：任务配置（并发/批大小/重试）、目标表结构、
 * 启用的映射都可能在"暂停→恢复"之间变化。缓存上下文会让恢复后的消费者继续用旧表结构写库，
 * 这类错误只有在目标端报"列不存在"时才会暴露，很难排查。
 *
 * <p><b>装配失败的粒度</b>（重要）：
 * <ul>
 *   <li>任务不存在 / 没有启用的表映射 → 直接抛 {@link DbSyncException}：这两件事说明"任务没准备好"，
 *       让消费者跑起来只会把每条变更都写进死信；</li>
 *   <li>单张目标表读不到元数据 → <b>不</b>整体失败：记 {@code log.error} + 跳过该映射，
 *       该表的事件由 {@code GroupWorker} 判定"未配置目标表元数据"后转死信（可见、可重放），
 *       其余表照常消费；</li>
 *   <li>所有目标表都读不到（目标库未就绪/全表缺失）→ 抛 {@code E3002}：此时跑起来毫无意义。</li>
 * </ul>
 */
public class CdcContextFactory implements ChangeConsumer.ContextFactory {

    private static final Logger log = LoggerFactory.getLogger(CdcContextFactory.class);

    /**
     * <b>默认</b>冲突策略（仅在"表级、任务级都没有配置"时使用）。
     *
     * <p>本轮修正：此前这里是唯一的策略来源（写死 {@code SOURCE_WINS}），
     * 导致 PRD CDC-06「三种策略按表可配」事实上没有实现。现在策略按
     * {@link CdcContext#policyOf(long)} 的优先级解析：
     * <b>表级</b>{@code sync_table_mapping.conflict_policy}（V3 迁移新增）
     * → <b>任务级</b>{@code sync_task.conflict_policy} → 本常量。
     *
     * <p>保留这个常量是为了让"什么都没有时用什么"在代码里可见且只有一处，
     * 而不是散落在 builder 的默认值里。
     */
    private static final ConflictPolicy DEFAULT_POLICY = ConflictPolicy.SOURCE_WINS;

    /** 批大小上限（防止配置笔误把一批拉到内存里）。 */
    private static final int MAX_BATCH_SIZE = 1000;

    /** 重试退避起始值。 */
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(200L);

    private static final String DEFAULT_OWNER = "cdc-local";

    private final DataSourceRegistry registry;
    private final SyncTaskDao syncTaskDao;
    private final TableMappingDao tableMappingDao;
    private final ChangeQueue changeQueue;
    private final WatermarkStore watermarkStore;
    private final CdcContext.DeadLetterWriter deadLetters;
    private final String ownerInstance;
    private final Duration groupLease;
    private final Duration pollInterval;

    /**
     * 构造。
     *
     * @param registry        数据源注册表（取目标库连接池与方言）
     * @param syncTaskDao     任务 DAO（取 groupCount/并发/批大小/重试）
     * @param tableMappingDao 表映射 DAO（取启用的映射）
     * @param changeQueue     变更队列（读写 {@code sync_change_log}）
     * @param watermarkStore  水位仓（组独占 + 水位推进）
     * @param deadLetters     死信写入端口（通常是 {@code DeadLetterService::create}）
     * @param ownerInstance   消费者 ID（= 本实例 ID，同时是组租约持有者）
     * @param groupLease      组租期
     * @param pollInterval    空转轮询间隔
     */
    public CdcContextFactory(DataSourceRegistry registry, SyncTaskDao syncTaskDao,
                             TableMappingDao tableMappingDao, ChangeQueue changeQueue,
                             WatermarkStore watermarkStore, CdcContext.DeadLetterWriter deadLetters,
                             String ownerInstance, Duration groupLease, Duration pollInterval) {
        this.registry = registry;
        this.syncTaskDao = syncTaskDao;
        this.tableMappingDao = tableMappingDao;
        this.changeQueue = changeQueue;
        this.watermarkStore = watermarkStore;
        this.deadLetters = deadLetters;
        this.ownerInstance = ownerInstance == null || ownerInstance.isBlank()
                ? DEFAULT_OWNER : ownerInstance;
        this.groupLease = groupLease == null ? Duration.ofSeconds(30L) : groupLease;
        this.pollInterval = pollInterval == null ? Duration.ofMillis(200L) : pollInterval;
    }

    /**
     * 装配一个任务的消费上下文。
     *
     * @param taskId     任务 ID
     * @param stopSignal 停止信号（<b>原样透传</b>给 {@link CdcContext}，消费者优雅收手只靠它）
     * @return 上下文
     * @throws DbSyncException 任务不存在（{@code E3004}）/ 无启用映射（{@code E1001}）/
     *                         目标库全表不可用（{@code E3002}）/ 借连接失败（{@code E2002}）
     */
    @Override
    public CdcContext create(long taskId, BooleanSupplier stopSignal) {
        SyncTask task = loadTask(taskId);
        List<TableMapping> mappings = tableMappingDao.findEnabledByTask(taskId);
        if (mappings.isEmpty()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID,
                    "任务没有启用的表映射，无法启动增量消费")
                    .with("taskId", Long.valueOf(taskId));
        }
        long targetDsId = task.getTargetDsId();
        DataSourceHandle target = registry.handle(targetDsId);
        Dialect dialect = target.dialect();
        Map<Long, TableMeta> tables = loadTables(taskId, targetDsId, target, dialect, mappings);
        if (tables.isEmpty()) {
            throw new DbSyncException(ErrorCode.PRECHECK_FAILED,
                    "目标表元数据全部不可用（共 " + mappings.size() + " 个启用映射），无法消费增量变更")
                    .with("taskId", Long.valueOf(taskId)).with("dsId", Long.valueOf(targetDsId));
        }
        int concurrency = Math.max(1, task.getConsumerConcurrency());
        // 任务级策略：sync_task.conflict_policy（DB 列为 NOT NULL DEFAULT 'SOURCE_WINS'）
        ConflictPolicy taskPolicy = task.getConflictPolicy() == null
                ? DEFAULT_POLICY : task.getConflictPolicy();
        Map<Long, ConflictPolicy> tablePolicies = resolvePolicies(mappings, taskPolicy);
        log.info("增量上下文已装配 task={} groups={} concurrency={} tables={}/{} owner={} "
                        + "taskPolicy={} overriddenTables={}", taskId, task.getGroupCount(),
                concurrency, tables.size(), mappings.size(), ownerInstance, taskPolicy,
                Integer.valueOf(countOverridden(tablePolicies, taskPolicy)));
        return CdcContext.builder()
                .taskId(taskId)
                .groupCount(task.getGroupCount())
                .consumerConcurrency(concurrency)
                .owner(ownerInstance)
                .queue(changeQueue)
                .watermarks(watermarkStore)
                .resolver(new ConflictResolver(dialect))
                .applier(new UpsertApplier())
                .dialect(dialect)
                .tables(tables)
                .policy(taskPolicy)
                .policies(tablePolicies)
                .batchSize(batchSizeOf(task))
                .retry(new RetryTemplate(Math.max(1, task.getMaxRetry()), RETRY_BACKOFF, 2.0d, 0.2d))
                .deadLetters(deadLetters)
                // 每次借连接都重新取句柄：数据源配置变更后旧池会被摘除，捕获住句柄会用上已关闭的池
                .targetConnections(() -> registry.handle(targetDsId).connection())
                .stopSignal(stopSignal)
                .groupLease(groupLease)
                .pollInterval(pollInterval)
                .build();
    }

    /**
     * 逐映射解析表级冲突策略。
     *
     * <p>只把"与任务级不同"的映射放进 map（相同的不放，减少无意义的内存占用）；
     * {@link CdcContext#policyOf(long)} 查不到时自然回落到任务级，语义不变。
     *
     * <p>表级值来自 {@code TableMappingDao} 的 {@code ConflictPolicy.parse}，
     * 库里的非法值在那里就已经抛 {@code E1001}——这里不做二次兜底，
     * 否则"库里写了个错策略"会被静默当成 {@code SOURCE_WINS}。
     *
     * @param mappings   启用的映射
     * @param taskPolicy 任务级策略（表级未配置时的值）
     * @return 表映射 ID → 策略
     */
    private static Map<Long, ConflictPolicy> resolvePolicies(List<TableMapping> mappings,
                                                             ConflictPolicy taskPolicy) {
        Map<Long, ConflictPolicy> policies = new LinkedHashMap<>();
        for (TableMapping mapping : mappings) {
            ConflictPolicy tablePolicy = mapping.getConflictPolicy() == null
                    ? taskPolicy : mapping.getConflictPolicy();
            if (tablePolicy != taskPolicy) {
                policies.put(Long.valueOf(mapping.getId()), tablePolicy);
            }
        }
        return policies;
    }

    /**
     * 统计表级覆盖了任务级的映射数（仅日志用）。
     *
     * @param tablePolicies 表级策略
     * @param taskPolicy    任务级策略
     * @return 数量
     */
    private static int countOverridden(Map<Long, ConflictPolicy> tablePolicies,
                                       ConflictPolicy taskPolicy) {
        int count = 0;
        for (ConflictPolicy policy : tablePolicies.values()) {
            if (policy != taskPolicy) {
                count++;
            }
        }
        return count;
    }

    /**
     * 读任务；不存在时抛 {@code E3004}。
     *
     * @param taskId 任务 ID
     * @return 任务
     */
    private SyncTask loadTask(long taskId) {
        SyncTask task = syncTaskDao.findById(taskId).orElseThrow(
                () -> new DbSyncException(ErrorCode.TASK_NOT_FOUND, Long.valueOf(taskId)));
        return task;
    }

    /**
     * 批大小：取任务配置，收敛到 {@code 1..1000}。
     *
     * @param task 任务
     * @return 批大小
     */
    private static int batchSizeOf(SyncTask task) {
        int configured = task.getBatchSize();
        if (configured <= 0) {
            return 200;
        }
        return Math.min(configured, MAX_BATCH_SIZE);
    }

    /**
     * 逐个映射读取目标表元数据（单表失败只跳过该表）。
     *
     * @param taskId     任务 ID（日志/异常上下文）
     * @param targetDsId 目标数据源 ID（日志/异常上下文）
     * @param target     目标库句柄
     * @param dialect    目标方言
     * @param mappings   启用的映射
     * @return 表映射 ID → 目标表元数据
     */
    private Map<Long, TableMeta> loadTables(long taskId, long targetDsId, DataSourceHandle target,
                                            Dialect dialect, List<TableMapping> mappings) {
        Map<Long, TableMeta> tables = new LinkedHashMap<>();
        Connection connection = null;
        try {
            connection = target.connection();
            for (TableMapping mapping : mappings) {
                TableMeta meta = loadOneTable(taskId, dialect, connection, mapping);
                if (meta != null) {
                    tables.put(Long.valueOf(mapping.getId()), meta);
                }
            }
            return tables;
        } catch (SQLException e) {
            log.error("增量上下文装配失败：借目标库连接异常 task={} dsId={} err={}", taskId,
                    targetDsId, e.toString(), e);
            throw new DbSyncException(ErrorCode.CONN_NETWORK_FAILED,
                    "增量消费无法连接目标库：" + e.getMessage()).with("taskId", Long.valueOf(taskId))
                    .with("dsId", Long.valueOf(targetDsId));
        } finally {
            closeQuietly(connection);
        }
    }

    /**
     * 读单张目标表元数据。
     *
     * @param taskId  任务 ID
     * @param dialect 目标方言
     * @param conn    目标库连接
     * @param mapping 表映射
     * @return 表元数据；表不存在/无主键/读失败时返回 null（调用方跳过该映射）
     */
    private TableMeta loadOneTable(long taskId, Dialect dialect, Connection conn,
                                   TableMapping mapping) {
        String table = qualified(mapping.getTargetSchema(), mapping.getTargetTable());
        TableMeta meta;
        try {
            meta = dialect.metadata().loadTable(conn, mapping.getTargetSchema(),
                    mapping.getTargetTable());
        } catch (SQLException e) {
            log.error("读取目标表元数据失败，跳过该映射 task={} mapping={} table={} err={}", taskId,
                    mapping.getId(), table, e.toString());
            return null;
        }
        if (meta == null || meta.getColumns().isEmpty()) {
            log.error("目标表不存在（或不可见），跳过该映射 task={} mapping={} table={}", taskId,
                    mapping.getId(), table);
            return null;
        }
        if (!meta.hasPrimaryKey()) {
            // 无主键无法幂等 upsert：让事件走死信而不是"猜一行去覆盖"
            log.error("目标表无主键，无法幂等 upsert，跳过该映射 task={} mapping={} table={}", taskId,
                    mapping.getId(), table);
            return null;
        }
        return meta;
    }

    /**
     * @param schema schema（可空）
     * @param table  表名
     * @return {@code schema.table} 或表名
     */
    private static String qualified(String schema, String table) {
        return schema == null || schema.isBlank() ? table : schema + "." + table;
    }

    /**
     * 静默归还连接。
     *
     * @param connection 连接（可 null）
     */
    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("归还目标库连接失败（忽略）：{}", e.getMessage());
        }
    }
}
