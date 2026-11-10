package com.dbsync.cdc;

import com.dbsync.common.enums.ChangeOp;
import com.dbsync.common.enums.ChangeStatus;
import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.ChangeEvent;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.util.HashUtil;
import com.dbsync.common.util.SnowflakeId;
import com.dbsync.core.datasource.DataSourceHandle;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.dialect.Dialect;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 时间戳轮询器（第三模式：能力已实现，但有两个必须先说清的短板）。
 *
 * <p><b>短板一：抓不到 DELETE</b>。行被删掉后时间戳也随之消失，
 * 按时间戳轮询只能看到"当前仍存在的行"。本模式只适合"只增不删"，
 * 或"删除由业务侧另行补偿"的表。
 *
 * <p><b>短板二：无法区分 INSERT / UPDATE</b>。一律按 UPDATE 处理（= upsert），
 * 数据结果正确，但审计里的"操作类型"会失真。
 *
 * <p>正因为这两点，平台默认模式是触发器日志（{@code TRIGGER_LOG}），
 * 时间戳模式是"源库不允许建触发器"时的兜底。这两个短板写在这里而不是藏进 Issue，
 * 是因为选型决定必须让运维看得见。
 *
 * <p><b>去重</b>：查询带 {@link #OVERLAP} 宽的重叠窗口（覆盖时钟回拨与长事务晚提交），
 * 再按 {@code (主键 → 已处理的最大时间戳)} 过滤，保证同一行不会因窗口重叠被重复应用。
 *
 * <p><b>位点持久化</b>（本轮修好的问题）：{@code commit_ts} 位点通过
 * {@link PollerCursorStore} 落到 {@code sync_checkpoint(scope_type='POLLER')} 的
 * {@code last_commit_ts}。此前位点在进程内，重启后从"当前时间 - OVERLAP"重新开始，
 * <b>停机窗口内的变更会被静默漏掉</b>——而漏采比重复采严重得多。
 *
 * <p><b>仍然存在的局限（诚实声明）</b>：{@code seenByMapping}（主键 → 已处理时间戳）
 * 是纯进程内状态。重启后重叠窗口内 {@code OVERLAP} 之前已被处理的行会被重新产出一次；
 * 因为产出的都是 {@code UPDATE}（upsert 语义），数据结果仍正确，只是多写一遍。
 * 把它也持久化需要在 {@code sync_checkpoint} 之外再落一张"每主键位点"表，
 * 对"源库不允许建触发器"的兜底模式不值得，故保留。
 */
public class TimestampPoller implements ChangePoller {

    private static final Logger log = LoggerFactory.getLogger(TimestampPoller.class);

    /** 重叠窗口：覆盖时钟回拨与长事务晚提交（架构 §4.3.5 建议 5s）。 */
    static final Duration OVERLAP = Duration.ofSeconds(5L);

    /** 时间戳列候选（与 {@code ConflictResolver} 保持一致，避免两处判断不同）。 */
    private static final List<String> TS_COLUMNS = List.of("update_time", "updated_at",
            "modify_time", "gmt_modified");

    private final DataSourceRegistry registry;
    private final SyncTaskDao syncTaskDao;
    private final TableMappingDao tableMappingDao;
    private final ChangeQueue queue;
    private final PollerCursorStore cursors;
    private final GroupAssigner groupAssigner = new GroupAssigner();
    private final SnowflakeId idGenerator = new SnowflakeId(SnowflakeId.defaultWorkerId());
    /** 位点的诊断镜像（真相在 {@link PollerCursorStore}）。 */
    private final Map<Long, Instant> cursorByMapping = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Instant>> seenByMapping = new ConcurrentHashMap<>();
    private final int batchSize;

    /**
     * 构造（位点只存进程内 —— <b>仅单测</b>，生产请用 6 参构造）。
     *
     * @param registry        数据源注册表
     * @param syncTaskDao     任务 DAO
     * @param tableMappingDao 表映射 DAO
     * @param queue           变更队列
     * @param batchSize       批大小
     */
    public TimestampPoller(DataSourceRegistry registry, SyncTaskDao syncTaskDao,
                           TableMappingDao tableMappingDao, ChangeQueue queue, int batchSize) {
        this(registry, syncTaskDao, tableMappingDao, queue, batchSize, new InMemoryCursorStore());
    }

    /**
     * 构造。
     *
     * @param registry        数据源注册表
     * @param syncTaskDao     任务 DAO
     * @param tableMappingDao 表映射 DAO
     * @param queue           变更队列
     * @param batchSize       批大小
     * @param cursors         位点存储（生产为 {@link CheckpointCursorStore}，不可 null）
     */
    public TimestampPoller(DataSourceRegistry registry, SyncTaskDao syncTaskDao,
                           TableMappingDao tableMappingDao, ChangeQueue queue, int batchSize,
                           PollerCursorStore cursors) {
        this.registry = registry;
        this.syncTaskDao = syncTaskDao;
        this.tableMappingDao = tableMappingDao;
        this.queue = queue;
        this.batchSize = batchSize <= 0 ? 500 : batchSize;
        this.cursors = cursors == null ? new InMemoryCursorStore() : cursors;
    }

    /**
     * @return 本实现负责的增量模式
     */
    @Override
    public IncrementalMode mode() {
        return IncrementalMode.TIMESTAMP_VERSION;
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
        // 采集权窗口取任务轮询周期（与 TriggerLogPoller 同一规则）
        Duration claimWindow = task.getPollIntervalMs() > 0
                ? Duration.ofMillis(task.getPollIntervalMs()) : null;
        if (!cursors.tryClaim(taskId, tableMappingId, claimWindow)) {
            log.debug("本轮采集权已被其他实例认领，跳过 task={} mapping={}", taskId, tableMappingId);
            return 0;
        }
        TableMapping mapping = tableMappingDao.findById(tableMappingId)
                .orElseThrow(() -> new DbSyncException(ErrorCode.PARAM_INVALID,
                        "表映射不存在：" + tableMappingId));
        DataSourceHandle source = registry.handle(task.getSourceDsId());
        TableMeta table = source.withConnection(connection -> source.dialect().metadata()
                .loadTable(connection, mapping.getSourceSchema(), mapping.getSourceTable()));
        if (table == null) {
            throw new DbSyncException(ErrorCode.PRECHECK_FAILED,
                    "源表不存在：" + mapping.getSourceTable());
        }
        ColumnMeta tsColumn = timestampColumn(table);
        if (tsColumn == null) {
            throw new DbSyncException(ErrorCode.EXTERNAL_NOT_IMPLEMENTED,
                    "时间戳模式要求源表 " + mapping.getSourceTable()
                            + " 存在 update_time/updated_at 列，当前表没有；请改用 TRIGGER_LOG 模式");
        }
        Long key = Long.valueOf(tableMappingId);
        // 位点真相在持久化存储：重启后接着上次的 commit_ts 继续，而不是"从现在开始"
        Instant stored = cursors.load(taskId, tableMappingId).lastCommitTs();
        Instant cursor = stored == null ? Instant.EPOCH : stored;
        cursorByMapping.put(key, cursor);
        Instant from = Instant.EPOCH.equals(cursor) ? Instant.now().minus(OVERLAP)
                : cursor.minus(OVERLAP);

        List<ChangeEvent> events = source.withConnection(connection -> read(connection,
                source.dialect(), mapping, table, tsColumn, from, task.getGroupCount(), key));
        if (events.isEmpty()) {
            return 0;
        }
        queue.enqueue(events);
        Instant maxTs = cursor;
        for (ChangeEvent event : events) {
            if (event.getCommitTs().isAfter(maxTs)) {
                maxTs = event.getCommitTs();
            }
        }
        cursors.advance(taskId, tableMappingId, null, maxTs);
        cursorByMapping.put(key, maxTs);
        log.debug("时间戳轮询入队 task={} mapping={} count={} cursor={}", taskId, tableMappingId,
                events.size(), maxTs);
        return events.size();
    }

    /**
     * 当前位点（诊断用）。
     *
     * @param tableMappingId 表映射 ID
     * @return 位点
     */
    public Instant cursor(long tableMappingId) {
        return cursorByMapping.getOrDefault(Long.valueOf(tableMappingId), Instant.EPOCH);
    }

    /**
     * 已见主键数（诊断/去重效果观察）。
     *
     * @param tableMappingId 表映射 ID
     * @return 数量
     */
    public int seenCount(long tableMappingId) {
        Map<String, Instant> seen = seenByMapping.get(Long.valueOf(tableMappingId));
        return seen == null ? 0 : seen.size();
    }

    /**
     * 清空<b>进程内</b>的诊断镜像与去重状态（单测用）。
     *
     * <p><b>注意</b>：<b>不</b>清除持久化的 {@code commit_ts} 位点——它落在
     * {@code sync_checkpoint(POLLER)} 上，要真正"重新全量校准"必须连同那一行一起清
     * （{@code DELETE FROM sync_checkpoint WHERE task_id=? AND scope_type='POLLER'}），
     * 否则下一轮 {@link #pollOnce} 仍会从库里的位点继续。这里不自动删库是刻意的：
     * 一个叫 {@code reset} 的方法顺手删掉生产位点，误调用一次就会重放整表。
     *
     * @param tableMappingId 表映射 ID
     */
    public void reset(long tableMappingId) {
        Long key = Long.valueOf(tableMappingId);
        cursorByMapping.remove(key);
        seenByMapping.remove(key);
    }

    /**
     * 读取增量行。
     *
     * @param connection 连接
     * @param dialect    方言（标识符引号化只走方言）
     * @param mapping    映射
     * @param table      源表
     * @param tsColumn   时间列
     * @param from       起始时间（含重叠窗口）
     * @param groupCount 组数
     * @param key        映射键（去重表）
     * @return 事件
     * @throws SQLException 读取失败
     */
    List<ChangeEvent> read(Connection connection, Dialect dialect, TableMapping mapping,
                                   TableMeta table, ColumnMeta tsColumn, Instant from,
                                   int groupCount, Long key) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT ");
        List<ColumnMeta> columns = table.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append(dialect.quote(columns.get(i).getName()));
        }
        sql.append(" FROM ").append(qualified(dialect, table))
                .append(" WHERE ").append(dialect.quote(tsColumn.getName())).append(" >= ?")
                .append(" ORDER BY ").append(dialect.quote(tsColumn.getName()));
        List<ChangeEvent> events = new ArrayList<>();
        Map<String, Instant> seen = seenByMapping.computeIfAbsent(key,
                k -> new ConcurrentHashMap<String, Instant>());
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            ps.setTimestamp(1, Timestamp.from(from));
            ps.setMaxRows(batchSize);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int tsIndex = indexOf(meta, tsColumn.getName());
                while (rs.next()) {
                    Instant ts = rs.getTimestamp(tsIndex).toInstant();
                    Map<String, Object> row = readRow(rs, meta);
                    List<Object> pkTuple = pkTupleOf(table, row);
                    String pkKey = HashUtil.canonical(pkTuple);
                    Instant applied = seen.get(pkKey);
                    if (applied != null && !ts.isAfter(applied)) {
                        // 重叠窗口内的旧行：已处理过，跳过（去重的唯一判据）
                        continue;
                    }
                    seen.put(pkKey, ts);
                    int group = groupAssigner.groupOf(pkTuple, table.primaryKeyColumns(),
                            groupCount);
                    events.add(new ChangeEvent(idGenerator.nextId(), mapping.getTaskId(),
                            mapping.getId(), group, HashUtil.pkHash(pkTuple),
                            ts.toEpochMilli(), ts, ChangeOp.UPDATE, pkTuple, null, row, null,
                            ChangeStatus.NEW, 0, null, null));
                }
            }
        }
        return events;
    }

    /**
     * 读一行到 map（键统一大写，供 {@code UpsertApplier} 大小写不敏感取值）。
     *
     * @param rs   结果集
     * @param meta 元数据
     * @return 行
     * @throws SQLException 读取失败
     */
    private Map<String, Object> readRow(ResultSet rs, ResultSetMetaData meta) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            row.put(meta.getColumnLabel(i).toUpperCase(Locale.ROOT), rs.getObject(i));
        }
        return row;
    }

    /**
     * 取主键元组。
     *
     * @param table 表
     * @param row   行
     * @return 元组
     */
    private List<Object> pkTupleOf(TableMeta table, Map<String, Object> row) {
        List<Object> tuple = new ArrayList<>();
        for (String pk : table.primaryKeyNames()) {
            tuple.add(row.get(pk.toUpperCase(Locale.ROOT)));
        }
        return tuple;
    }

    /**
     * 定位时间列下标。
     *
     * @param meta 元数据
     * @param name 列名
     * @return 下标
     * @throws SQLException 找不到
     */
    private int indexOf(ResultSetMetaData meta, String name) throws SQLException {
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            if (meta.getColumnLabel(i).equalsIgnoreCase(name)) {
                return i;
            }
        }
        throw new DbSyncException(ErrorCode.CHANGE_APPLY_FAILED, "结果集中找不到时间列：" + name);
    }

    /**
     * 找时间列。
     *
     * @param table 表
     * @return 列；没有返回 null
     */
    static ColumnMeta timestampColumn(TableMeta table) {
        for (String candidate : TS_COLUMNS) {
            for (ColumnMeta column : table.getColumns()) {
                if (column.getName().toLowerCase(Locale.ROOT).equals(candidate)) {
                    return column;
                }
            }
        }
        return null;
    }

    /**
     * 拼引号全名。
     *
     * @param dialect 方言
     * @param table   表
     * @return 全名
     */
    private static String qualified(Dialect dialect, TableMeta table) {
        String schema = table.getSchema();
        if (schema == null || schema.isBlank()) {
            return dialect.quote(table.getTableName());
        }
        return dialect.quote(schema) + "." + dialect.quote(table.getTableName());
    }
}
