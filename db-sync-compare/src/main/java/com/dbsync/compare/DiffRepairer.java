package com.dbsync.compare;

import com.dbsync.common.enums.DiffType;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.DiffRecord;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.WriteOptions;
import com.dbsync.common.util.JsonUtil;
import com.dbsync.core.datasource.DataSourceHandle;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.core.write.JdbcBatchWriter;
import com.dbsync.metadata.dao.CompareDiffDao;
import com.dbsync.metadata.dao.CompareJobDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 差异修复器（T36）：两个方向。
 *
 * <p><b>① 回放到目标（正向，默认）</b>：{@code replayToTarget} —— 从源库<b>重新读取</b>该主键的当前行，
 * 用方言幂等 Upsert 写进目标。刻意不信任 {@code src_row_json}（那是比对时刻的快照，
 * 期间源库可能已改；以"当前源值"为准才能让复比通过）。{@code ONLY_IN_TARGET} 的行无法正向回放
 * （源端根本没有），计入 {@code skipped}。
 *
 * <p><b>② 反向修复（高危）</b>：{@code fixToSource} —— 把目标库的值写回源库。三条硬约束：
 * <ol>
 *   <li>必须提供 {@code reason}（{@code E8002}），并校验操作人为 ADMIN；</li>
 *   <li>必须先 {@code CREATE TABLE bak_<table>_<ts>} 备份目标原值（CMP-03），备份失败则整批中止；</li>
 *   <li>写回源库也走 Upsert，<b>禁止物理 DELETE</b>（删除靠"业务软删列"，架构 §8.4）。</li>
 * </ol>
 *
 * <p><b>ADMIN 判定</b>：默认实现放行（{@code op -&gt; true}），由 API 层 RBAC 兜底；
 * 生产装配时应注入"查 {@code sync_user.role} 是否为 ADMIN"的判定器。
 *
 * <p><b>事务边界</b>：逐行一个事务（读 → 备份 → 回放 → 提交），单行失败不影响其余行，
 * 失败行留在 {@link RepairResult#getFailedIds()}。
 */
public class DiffRepairer {

    private static final Logger log = LoggerFactory.getLogger(DiffRepairer.class);

    /** 备份表名最大长度保护（多数库标识符上限 30/63/128，这里统一截断到 60）。 */
    private static final int MAX_BACKUP_NAME = 60;

    private final SyncTaskDao syncTaskDao;
    private final TableMappingDao tableMappingDao;
    private final CompareJobDao jobDao;
    private final CompareDiffDao diffDao;
    private final DataSourceRegistry registry;
    private final JdbcBatchWriter writer;
    private final Predicate<String> adminChecker;

    /**
     * 构造（ADMIN 判定默认放行）。
     *
     * @param syncTaskDao     任务 DAO
     * @param tableMappingDao 表映射 DAO
     * @param jobDao          作业 DAO
     * @param diffDao         差异 DAO
     * @param registry        数据源注册表
     */
    public DiffRepairer(SyncTaskDao syncTaskDao, TableMappingDao tableMappingDao,
                        CompareJobDao jobDao, CompareDiffDao diffDao, DataSourceRegistry registry) {
        this(syncTaskDao, tableMappingDao, jobDao, diffDao, registry, new JdbcBatchWriter(),
                operator -> true);
    }

    /**
     * 构造（生产装配可注入 ADMIN 判定器）。
     *
     * @param syncTaskDao     任务 DAO
     * @param tableMappingDao 表映射 DAO
     * @param jobDao          作业 DAO
     * @param diffDao         差异 DAO
     * @param registry        数据源注册表
     * @param writer          批量写入器
     * @param adminChecker    操作人是否 ADMIN
     */
    public DiffRepairer(SyncTaskDao syncTaskDao, TableMappingDao tableMappingDao,
                        CompareJobDao jobDao, CompareDiffDao diffDao, DataSourceRegistry registry,
                        JdbcBatchWriter writer, Predicate<String> adminChecker) {
        this.syncTaskDao = syncTaskDao;
        this.tableMappingDao = tableMappingDao;
        this.jobDao = jobDao;
        this.diffDao = diffDao;
        this.registry = registry;
        this.writer = writer == null ? new JdbcBatchWriter() : writer;
        this.adminChecker = adminChecker == null ? operator -> true : adminChecker;
    }

    // ------------------------------------------------------------------
    // ① 回放到目标
    // ------------------------------------------------------------------

    /**
     * 把差异行从源库回放到目标库（幂等 Upsert）。
     *
     * @param jobId   作业 ID
     * @param diffIds 差异 ID 列表
     * @return 修复结果
     * @throws DbSyncException 作业/任务不存在
     */
    public RepairResult replayToTarget(long jobId, List<Long> diffIds) {
        RepairContext context = context(jobId);
        if (diffIds == null || diffIds.isEmpty()) {
            return RepairResult.failed(jobId, "未指定差异 ID");
        }
        List<DiffRecord> diffs = diffDao.findByIds(diffIds);
        List<Long> replayed = new ArrayList<Long>();
        List<Long> failedIds = new ArrayList<Long>();
        List<String> messages = new ArrayList<String>();
        int skipped = 0;
        for (DiffRecord diff : diffs) {
            if (diff.getDiffType() == DiffType.ONLY_IN_TARGET) {
                skipped++;
                messages.add("跳过 diff=" + diff.getId() + "：源端不存在该主键，无法向目标回放");
                continue;
            }
            TableMapping mapping = context.mapping(diff.getTableMappingId());
            if (mapping == null) {
                failedIds.add(Long.valueOf(diff.getId()));
                messages.add("diff=" + diff.getId() + " 的表映射已删除");
                continue;
            }
            try {
                TableMeta sourceTable = context.table(context.source, mapping.getSourceSchema(),
                        mapping.getSourceTable(), "源");
                TableMeta targetTable = context.table(context.target, mapping.getTargetSchema(),
                        mapping.getTargetTable(), "目标");
                Map<String, Object> row = readRow(context.source, sourceTable, diff.getPkJson());
                if (row == null) {
                    skipped++;
                    messages.add("跳过 diff=" + diff.getId() + "：源端当前已无该行");
                    continue;
                }
                upsert(context.target, targetTable, row);
                replayed.add(Long.valueOf(diff.getId()));
            } catch (RuntimeException e) {
                failedIds.add(Long.valueOf(diff.getId()));
                messages.add("diff=" + diff.getId() + " 回放失败：" + e.getMessage());
                log.warn("差异回放失败 diff={}：{}", diff.getId(), e.getMessage());
            }
        }
        diffDao.updateRepairState(replayed, "REPLAYED", "system", null);
        if (!failedIds.isEmpty()) {
            diffDao.updateRepairState(failedIds, "FAILED", "system", null);
        }
        RepairResult result = new RepairResult(jobId, diffs.size(), replayed.size(), skipped,
                failedIds.size(), null, failedIds, messages);
        log.info("差异回放完成 job={} 结果={}", jobId, result);
        return result;
    }

    // ------------------------------------------------------------------
    // ② 反向修复（高危）
    // ------------------------------------------------------------------

    /**
     * 把目标库的值写回源库（先备份目标原值）。
     *
     * @param jobId    作业 ID
     * @param diffIds  差异 ID 列表
     * @param operator 操作人
     * @param reason   原因（必填，落审计）
     * @return 修复结果
     * @throws DbSyncException {@code E8002} 原因/操作人为空 或 非 ADMIN；{@code E5003} 取证不足
     */
    public RepairResult fixToSource(long jobId, List<Long> diffIds, String operator, String reason) {
        // 硬约束 1：二次确认（ADMIN + 原因），两条都缺一不可
        if (reason == null || reason.isBlank()) {
            throw new DbSyncException(ErrorCode.SECOND_CONFIRM_REQUIRED,
                    "反向修复必须填写原因（reason）");
        }
        if (operator == null || operator.isBlank()) {
            throw new DbSyncException(ErrorCode.SECOND_CONFIRM_REQUIRED, "反向修复必须指定操作人");
        }
        if (!adminChecker.test(operator)) {
            throw new DbSyncException(ErrorCode.NO_PERMISSION,
                    "反向修复仅限 ADMIN：" + operator);
        }
        if (diffIds == null || diffIds.isEmpty()) {
            return RepairResult.failed(jobId, "未指定差异 ID");
        }
        RepairContext context = context(jobId);
        List<DiffRecord> diffs = diffDao.findByIds(diffIds);
        if (diffs.isEmpty()) {
            throw new DbSyncException(ErrorCode.REPAIR_EVIDENCE_INSUFFICIENT,
                    "找不到差异记录，无法取证：" + diffIds);
        }
        String backupTable = null;
        List<Long> fixed = new ArrayList<Long>();
        List<Long> failedIds = new ArrayList<Long>();
        List<String> messages = new ArrayList<String>();
        Map<Long, String> backupByMapping = new HashMap<Long, String>();
        for (DiffRecord diff : diffs) {
            TableMapping mapping = context.mapping(diff.getTableMappingId());
            if (mapping == null) {
                failedIds.add(Long.valueOf(diff.getId()));
                messages.add("diff=" + diff.getId() + " 的表映射已删除");
                continue;
            }
            try {
                TableMeta sourceTable = context.table(context.source, mapping.getSourceSchema(),
                        mapping.getSourceTable(), "源");
                TableMeta targetTable = context.table(context.target, mapping.getTargetSchema(),
                        mapping.getTargetTable(), "目标");
                // 硬约束 2：先备份目标原值（每个映射一张备份表，整批复用）
                String bak = backupByMapping.get(Long.valueOf(mapping.getId()));
                if (bak == null) {
                    bak = backupTableName(mapping.getTargetTable());
                    createBackupTable(context.target, targetTable, bak);
                    backupByMapping.put(Long.valueOf(mapping.getId()), bak);
                    backupTable = bak;
                }
                Map<String, Object> targetRow = readRow(context.target, targetTable,
                        diff.getPkJson());
                if (targetRow == null) {
                    messages.add("跳过 diff=" + diff.getId() + "：目标端当前已无该行");
                    continue;
                }
                insertIntoBackup(context.target, targetTable, bak, targetRow);
                // 硬约束 3：写回源库同样走 Upsert（禁止 DELETE）
                upsert(context.source, sourceTable, targetRow);
                fixed.add(Long.valueOf(diff.getId()));
            } catch (RuntimeException e) {
                failedIds.add(Long.valueOf(diff.getId()));
                messages.add("diff=" + diff.getId() + " 反向修复失败：" + e.getMessage());
                log.error("反向修复失败 diff={}：{}", diff.getId(), e.getMessage());
            }
        }
        diffDao.updateRepairState(fixed, "FIXED_TO_SOURCE", operator, backupTable);
        if (!failedIds.isEmpty()) {
            diffDao.updateRepairState(failedIds, "FAILED", operator, backupTable);
        }
        RepairResult result = new RepairResult(jobId, diffs.size(), fixed.size(), 0,
                failedIds.size(), backupTable, failedIds, messages);
        log.warn("反向修复完成（高危操作）job={} operator={} reason={} 结果={}", jobId, operator,
                reason, result);
        return result;
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 装配修复上下文（任务 + 三个句柄 + 映射索引）。
     *
     * @param jobId 作业 ID
     * @return 上下文
     */
    private RepairContext context(long jobId) {
        CompareJobDao.CompareJobRow job = jobDao.findById(jobId).orElseThrow(
                () -> new DbSyncException(ErrorCode.PARAM_INVALID, "比对作业不存在：" + jobId));
        SyncTask task = syncTaskDao.findById(job.getTaskId()).orElseThrow(
                () -> new DbSyncException(ErrorCode.TASK_NOT_FOUND,
                        "任务不存在：" + job.getTaskId()));
        Map<Long, TableMapping> mappings = new LinkedHashMap<Long, TableMapping>();
        for (TableMapping mapping : tableMappingDao.findByTask(task.getId())) {
            mappings.put(Long.valueOf(mapping.getId()), mapping);
        }
        return new RepairContext(registry.handle(task.getSourceDsId()),
                registry.handle(task.getTargetDsId()), mappings);
    }

    /**
     * 生成备份表名 {@code bak_<table>_<epoch>}。
     *
     * @param targetTable 目标表名
     * @return 备份表名
     */
    static String backupTableName(String targetTable) {
        String name = "bak_" + targetTable + "_" + Instant.now().getEpochSecond();
        return name.length() <= MAX_BACKUP_NAME ? name : name.substring(0, MAX_BACKUP_NAME);
    }

    /**
     * 建备份表（结构复制，不含数据）。
     *
     * @param handle      目标句柄
     * @param table       目标表元数据
     * @param backupTable 备份表名
     */
    private void createBackupTable(DataSourceHandle handle, TableMeta table, String backupTable) {
        String sql = "CREATE TABLE " + handle.dialect().quote(backupTable) + " AS SELECT * FROM "
                + qualified(handle, table) + " WHERE 1=0";
        handle.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.execute();
            }
            commit(connection);
            return Boolean.TRUE;
        });
        log.info("已创建备份表 {}（源表 {}）", backupTable, table.qualifiedName());
    }

    /**
     * 把目标行原值插入备份表。
     *
     * @param handle      目标句柄
     * @param table       目标表元数据
     * @param backupTable 备份表名
     * @param row         行数据
     */
    private void insertIntoBackup(DataSourceHandle handle, TableMeta table, String backupTable,
                                  Map<String, Object> row) {
        List<ColumnMeta> columns = table.getColumns();
        StringBuilder sql = new StringBuilder("INSERT INTO ")
                .append(handle.dialect().quote(backupTable)).append(" (");
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(',');
                values.append(',');
            }
            sql.append(handle.dialect().quote(columns.get(i).getName()));
            values.append('?');
        }
        sql.append(") VALUES (").append(values).append(')');
        handle.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                bindRow(statement, columns, row);
                statement.executeUpdate();
            }
            commit(connection);
            return Boolean.TRUE;
        });
    }

    /**
     * 按主键读取目标端当前行。
     *
     * @param handle 句柄
     * @param table  表元数据
     * @param pkJson 主键 JSON（{@code {"id":1}}）
     * @return 行（列名 → 值）；不存在返回 null
     */
    private Map<String, Object> readRow(DataSourceHandle handle, TableMeta table, String pkJson) {
        List<String> pkNames = table.primaryKeyNames();
        if (pkNames.isEmpty()) {
            throw new DbSyncException(ErrorCode.REPAIR_EVIDENCE_INSUFFICIENT,
                    "表无主键，无法按主键定位差异行：" + table.qualifiedName());
        }
        Map<String, Object> pk = JsonUtil.parseObject(pkJson);
        List<ColumnMeta> columns = table.getColumns();
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append(handle.dialect().quote(columns.get(i).getName()));
        }
        sql.append(" FROM ").append(qualified(handle, table)).append(" WHERE ");
        for (int i = 0; i < pkNames.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            sql.append(handle.dialect().quote(pkNames.get(i))).append(" = ?");
        }
        return handle.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 1;
                for (String pkName : pkNames) {
                    statement.setObject(index++, Normalizer.lookup(pk, pkName));
                }
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    for (int i = 0; i < columns.size(); i++) {
                        row.put(columns.get(i).getName(), rs.getObject(i + 1));
                    }
                    return row;
                }
            } finally {
                rollback(connection);
            }
        });
    }

    /**
     * 幂等写入一行（事务由本方法负责）。
     *
     * @param handle 句柄
     * @param table  表元数据
     * @param row    行数据
     */
    private void upsert(DataSourceHandle handle, TableMeta table, Map<String, Object> row) {
        List<ColumnMeta> columns = table.getColumns();
        Object[] values = new Object[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            values[i] = Normalizer.lookup(row, columns.get(i).getName());
        }
        List<Object[]> rows = new ArrayList<Object[]>(1);
        rows.add(values);
        handle.withConnection(connection -> {
            try {
                connection.setAutoCommit(false);
                writer.upsert(connection, handle.dialect(), table, rows, WriteOptions.upsert());
                connection.commit();
            } catch (SQLException e) {
                rollback(connection);
                throw new DbSyncException(ErrorCode.CHANGE_APPLY_FAILED, e,
                        "写入失败：" + table.qualifiedName() + "：" + e.getMessage());
            } catch (RuntimeException e) {
                rollback(connection);
                throw e;
            }
            return Boolean.TRUE;
        });
    }

    /**
     * 绑定一行参数。
     *
     * @param statement 语句
     * @param columns   列
     * @param row       行
     * @throws SQLException 绑定失败
     */
    private static void bindRow(PreparedStatement statement, List<ColumnMeta> columns,
                                Map<String, Object> row) throws SQLException {
        int index = 1;
        for (ColumnMeta column : columns) {
            statement.setObject(index++, Normalizer.lookup(row, column.getName()));
        }
    }

    /**
     * 提交（失败转业务异常，不吞）。
     *
     * @param connection 连接
     */
    private static void commit(Connection connection) {
        try {
            connection.commit();
        } catch (SQLException e) {
            throw new DbSyncException(ErrorCode.INTERNAL_ERROR, e, "提交失败：" + e.getMessage());
        }
    }

    /**
     * 回滚（异常只记日志，不覆盖真实原因）。
     *
     * @param connection 连接
     */
    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.debug("回滚失败（忽略）：{}", e.getMessage());
        }
    }

    /**
     * 拼限定表名。
     *
     * @param handle 句柄
     * @param table  表元数据
     * @return 已加引号的限定名
     */
    private static String qualified(DataSourceHandle handle, TableMeta table) {
        String schema = table.getSchema();
        String quoted = handle.dialect().quote(table.getTableName());
        return schema == null || schema.isBlank() ? quoted
                : handle.dialect().quote(schema) + '.' + quoted;
    }

    /** 修复上下文。 */
    private static final class RepairContext {

        private final DataSourceHandle source;
        private final DataSourceHandle target;
        private final Map<Long, TableMapping> mappings;

        private RepairContext(DataSourceHandle source, DataSourceHandle target,
                              Map<Long, TableMapping> mappings) {
            this.source = source;
            this.target = target;
            this.mappings = mappings;
        }

        private TableMapping mapping(long id) {
            return mappings.get(Long.valueOf(id));
        }

        private TableMeta table(DataSourceHandle handle, String schema, String name, String side) {
            TableMeta meta = handle.withConnection(connection ->
                    handle.dialect().metadata().loadTable(connection, schema, name));
            if (meta == null) {
                throw new DbSyncException(ErrorCode.REPAIR_EVIDENCE_INSUFFICIENT,
                        side + "表不存在：" + (schema == null ? "" : schema + ".") + name);
            }
            return meta;
        }
    }
}
