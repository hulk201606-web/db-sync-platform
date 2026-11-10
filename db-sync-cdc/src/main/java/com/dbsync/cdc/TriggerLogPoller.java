package com.dbsync.cdc;

import com.dbsync.common.enums.ChangeOp;
import com.dbsync.common.enums.ChangeStatus;
import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.ChangeEvent;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.util.HashUtil;
import com.dbsync.common.util.JsonUtil;
import com.dbsync.common.util.SnowflakeId;
import com.dbsync.core.datasource.DataSourceHandle;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.dialect.TriggerDdlGenerator;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import com.dbsync.metadata.dao.TriggerDefDao;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 触发器日志轮询器：{@code log_id > last} 增量拉取，转换为 {@link ChangeEvent} 入队。
 *
 * <p><b>为什么用"日志表 + 轮询"而不是 binlog/LogMiner</b>（架构 §4.3.5）：
 * binlog 解析需要源库开 {@code ROW} 格式、需要额外权限、且 MySQL/Oracle/达梦/DB2 各有一套；
 * 日志表方案只用标准 SQL 与触发器，五库通吃，代价是多一张表与触发器开销。
 * 对"迁移期临时增量"这个场景，这个取舍是划算的。
 *
 * <p><b>不锁未提交行</b>：触发器把日志写在与业务 DML <b>同一个事务</b>里，
 * 所以"看不到日志"就等于"业务还没提交"，天然不会读到脏变更。
 *
 * <p><b>不锁未提交行</b>：触发器把日志写在与业务 DML <b>同一个事务</b>里，
 * 所以"看不到日志"就等于"业务还没提交"，天然不会读到脏变更。
 *
 * <p><b>位点持久化</b>（本轮修好的问题）：{@code log_id} 位点通过
 * {@link PollerCursorStore} 落到 {@code sync_checkpoint(scope_type='POLLER')}，
 * 生产实现是 {@link CheckpointCursorStore}。此前位点只存在进程内
 * {@code ConcurrentHashMap}，进程一重启就从日志表起点重放——在千万行表上
 * 等于每次发版都全表重扫一遍。
 *
 * <p><b>顺序</b>：先 {@code queue.enqueue(events)} 写 {@code sync_change_log}，
 * 再 {@code cursors.advance(...)} 推位点。反过来会"位点说采过了、变更没入队"=永久丢失；
 * 现在的最坏情况是"重复采集一批"，而应用层是 upsert/delete 幂等，只浪费 IO 不写错数据。
 *
 * <p><b>多实例</b>：{@link PollerCursorStore#tryClaim} 用位点行的乐观版本做本轮采集权 CAS，
 * 抢不到的实例本轮直接跳过（{@code log.debug}），因此同一张表的同一批日志
 * 不会被两个实例各搬一遍。
 */
public class TriggerLogPoller implements ChangePoller {

    private static final Logger log = LoggerFactory.getLogger(TriggerLogPoller.class);

    private final DataSourceRegistry registry;
    private final SyncTaskDao syncTaskDao;
    private final TableMappingDao tableMappingDao;
    private final TriggerDefDao triggerDefDao;
    private final ChangeQueue queue;
    private final PollerCursorStore cursors;
    private final GroupAssigner groupAssigner = new GroupAssigner();
    private final SnowflakeId idGenerator = new SnowflakeId(SnowflakeId.defaultWorkerId());
    /** 位点的<b>诊断镜像</b>（真相在 {@link PollerCursorStore}，这里只为日志/单测取值免一次查询）。 */
    private final Map<Long, Long> lastLogIdByMapping = new ConcurrentHashMap<>();
    private final int batchSize;

    /**
     * 构造（位点只存进程内 —— <b>仅单测</b>，生产请用 7 参构造）。
     *
     * @param registry        数据源注册表
     * @param syncTaskDao     任务 DAO
     * @param tableMappingDao 表映射 DAO
     * @param triggerDefDao   触发器台账 DAO
     * @param queue           变更队列
     * @param batchSize       每批拉取上限
     */
    public TriggerLogPoller(DataSourceRegistry registry, SyncTaskDao syncTaskDao,
                            TableMappingDao tableMappingDao, TriggerDefDao triggerDefDao,
                            ChangeQueue queue, int batchSize) {
        this(registry, syncTaskDao, tableMappingDao, triggerDefDao, queue, batchSize,
                new InMemoryCursorStore());
    }

    /**
     * 构造。
     *
     * @param registry        数据源注册表
     * @param syncTaskDao     任务 DAO
     * @param tableMappingDao 表映射 DAO
     * @param triggerDefDao   触发器台账 DAO
     * @param queue           变更队列
     * @param batchSize       每批拉取上限
     * @param cursors         位点存储（生产为 {@link CheckpointCursorStore}，不可 null）
     */
    public TriggerLogPoller(DataSourceRegistry registry, SyncTaskDao syncTaskDao,
                            TableMappingDao tableMappingDao, TriggerDefDao triggerDefDao,
                            ChangeQueue queue, int batchSize, PollerCursorStore cursors) {
        this.registry = registry;
        this.syncTaskDao = syncTaskDao;
        this.tableMappingDao = tableMappingDao;
        this.triggerDefDao = triggerDefDao;
        this.queue = queue;
        this.batchSize = batchSize <= 0 ? 500 : batchSize;
        this.cursors = cursors == null ? new InMemoryCursorStore() : cursors;
    }

    /**
     * @return 本实现负责的增量模式
     */
    @Override
    public IncrementalMode mode() {
        return IncrementalMode.TRIGGER_LOG;
    }

    /**
     * 拉取一批并入队。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @return 本次入队条数
     */
    @Override
    public int pollOnce(long taskId, long tableMappingId) {
        SyncTask task = syncTaskDao.findById(taskId)
                .orElseThrow(() -> new DbSyncException(ErrorCode.TASK_NOT_FOUND,
                        "任务不存在：" + taskId));
        // 采集权窗口取任务的轮询周期：既保证同实例按周期正常轮转（下一次认领刚好过窗口），
        // 又让另一实例在整个周期内抢不到（避免同一批日志被两个实例各搬一遍）。
        Duration claimWindow = task.getPollIntervalMs() > 0
                ? Duration.ofMillis(task.getPollIntervalMs()) : null;
        if (!cursors.tryClaim(taskId, tableMappingId, claimWindow)) {
            log.debug("本轮采集权已被其他实例认领，跳过 task={} mapping={}", taskId, tableMappingId);
            return 0;
        }
        TableMapping mapping = tableMappingDao.findById(tableMappingId)
                .orElseThrow(() -> new DbSyncException(ErrorCode.PARAM_INVALID,
                        "表映射不存在：" + tableMappingId));
        String logTable = triggerDefDao.findByTaskAndTable(taskId, tableMappingId)
                .map(def -> def.getLogTableName())
                .orElseThrow(() -> new DbSyncException(ErrorCode.TRIGGER_DEPLOY_FAILED,
                        "触发器尚未部署，无法轮询：mapping=" + tableMappingId));

        DataSourceHandle source = registry.handle(task.getSourceDsId());
        TriggerDdlGenerator generator = source.dialect().trigger();
        TableMeta table = loadTable(source, mapping);
        int groupCount = task.getGroupCount();
        // 位点真相在持久化存储：重启后这里读到的就是上次采到的位置
        long last = cursors.load(taskId, tableMappingId).lastLogId();

        String sql = generator.pollSql(source.dialect().quote(logTable), last, batchSize);
        List<ChangeEvent> events = source.withConnection(connection -> read(connection, sql,
                taskId, tableMappingId, table, groupCount));
        if (events.isEmpty()) {
            return 0;
        }
        queue.enqueue(events);
        long maxLogId = last;
        for (ChangeEvent event : events) {
            maxLogId = Math.max(maxLogId, event.getSeq());
        }
        // 先入队再推位点（见类注释"顺序"）
        cursors.advance(taskId, tableMappingId, Long.valueOf(maxLogId), null);
        lastLogIdByMapping.put(Long.valueOf(tableMappingId), Long.valueOf(maxLogId));
        log.debug("轮询入队 task={} mapping={} count={} lastLogId={}", taskId, tableMappingId,
                events.size(), maxLogId);
        return events.size();
    }

    /**
     * 当前位点（诊断/测试用）。
     *
     * @param tableMappingId 表映射 ID
     * @return log_id 位点
     */
    public long lastLogId(long tableMappingId) {
        Long value = lastLogIdByMapping.get(Long.valueOf(tableMappingId));
        return value == null ? 0L : value.longValue();
    }

    /**
     * 读到内存（连接借还由 withConnection 负责）。
     *
     * @param connection     源库连接
     * @param sql            轮询 SQL（表名已加引号、数值已内联）
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @param table          源表元数据
     * @param groupCount     组数
     * @return 事件列表（按 log_id 升序）
     * @throws SQLException 读取失败
     */
    List<ChangeEvent> read(java.sql.Connection connection, String sql, long taskId,
                                   long tableMappingId, TableMeta table, int groupCount)
            throws SQLException {
        List<ChangeEvent> events = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                events.add(toEvent(rs, taskId, tableMappingId, table, groupCount));
            }
        }
        return events;
    }

    /**
     * 日志行 → 变更事件。
     *
     * @param rs             结果集
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @param table          源表元数据
     * @param groupCount     组数
     * @return 事件
     * @throws SQLException 读取失败
     */
    private ChangeEvent toEvent(ResultSet rs, long taskId, long tableMappingId, TableMeta table,
                                int groupCount) throws SQLException {
        long logId = rs.getLong("id");
        String opText = rs.getString("op");
        ChangeOp op = parseOp(opText);
        List<Object> pkTuple = parseTuple(rs.getString("pk_json"));
        Map<String, Object> before = parseObject(rs.getString("before_json"));
        Map<String, Object> after = parseObject(rs.getString("after_json"));
        Timestamp commitTs = rs.getTimestamp("commit_ts");
        Instant ts = commitTs == null ? Instant.now() : commitTs.toInstant();
        int group = groupAssigner.groupOf(pkTuple, table.primaryKeyColumns(), groupCount);
        long pkHash = HashUtil.pkHash(pkTuple);
        // seq 用 log_id：日志表自增，天然全局单调，保证"批内无重复、批间无遗漏"
        return new ChangeEvent(idGenerator.nextId(), taskId, tableMappingId, group, pkHash, logId,
                ts, op, pkTuple, before.isEmpty() ? null : before,
                after.isEmpty() ? null : after, null, ChangeStatus.NEW, 0, null, null);
    }

    /**
     * 解析操作类型。
     *
     * @param text 文本
     * @return 操作
     */
    static ChangeOp parseOp(String text) {
        if (text == null || text.isBlank()) {
            throw new DbSyncException(ErrorCode.CHANGE_APPLY_FAILED, "日志行的 op 为空");
        }
        try {
            return ChangeOp.valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new DbSyncException(ErrorCode.CHANGE_APPLY_FAILED, "无法识别的 op：" + text);
        }
    }

    /**
     * 解析主键数组（触发器写的是 JSON_ARRAY）。
     *
     * @param json JSON 文本
     * @return 元组
     */
    static List<Object> parseTuple(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return JsonUtil.parseArray(json);
    }

    /**
     * 解析行镜像（触发器的 JSON_OBJECT）。
     *
     * @param json JSON 文本
     * @return 镜像（空表示无）
     */
    static Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return JsonUtil.parseObject(json);
    }

    /**
     * 读源表元数据。
     *
     * @param source  源句柄
     * @param mapping 映射
     * @return 表元数据
     */
    private TableMeta loadTable(DataSourceHandle source, TableMapping mapping) {
        Optional<TableMeta> loaded = Optional.ofNullable(source.withConnection(connection ->
                source.dialect().metadata().loadTable(connection, mapping.getSourceSchema(),
                        mapping.getSourceTable())));
        return loaded.orElseThrow(() -> new DbSyncException(ErrorCode.PRECHECK_FAILED,
                "源表不存在：" + mapping.getSourceTable()));
    }

}
