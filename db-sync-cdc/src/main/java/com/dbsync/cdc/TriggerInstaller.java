package com.dbsync.cdc;

import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.TriggerOptions;
import com.dbsync.common.util.JsonUtil;
import com.dbsync.core.datasource.DataSourceHandle;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.dialect.Dialect;
import com.dbsync.dialect.TriggerDdlGenerator;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import com.dbsync.metadata.dao.TriggerDefDao;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 触发器安装器：部署"变更日志表 + 3 个触发器"，并把 DDL 台账落到 {@code sync_trigger_def}。
 *
 * <p><b>DDL 全部来自 {@code dialect.trigger()}</b>（硬规则 3）：MySQL 要 3 个触发器、
 * Oracle 要 {@code :NEW}/{@code :OLD} 与 {@code TIMESTAMP}、PG 要函数 + 触发器 ——
 * 这些差异一行都不能出现在本类里。
 *
 * <p><b>幂等</b>：{@code install} 可重复调用。日志表 DDL 多数方言带 {@code IF NOT EXISTS}，
 * 但索引语句普遍没有；因此执行时把"已存在（already exists / ORA-00955 / 42S01）"
 * 视为成功（这正是幂等的定义：第二次 install 不改变状态）。
 */
public class TriggerInstaller {

    private static final Logger log = LoggerFactory.getLogger(TriggerInstaller.class);

    private final DataSourceRegistry registry;
    private final SyncTaskDao syncTaskDao;
    private final TableMappingDao tableMappingDao;
    private final TriggerDefDao triggerDefDao;

    /**
     * 构造。
     *
     * @param registry       数据源注册表
     * @param syncTaskDao    任务 DAO
     * @param tableMappingDao 表映射 DAO
     * @param triggerDefDao  触发器台账 DAO
     */
    public TriggerInstaller(DataSourceRegistry registry, SyncTaskDao syncTaskDao,
                            TableMappingDao tableMappingDao, TriggerDefDao triggerDefDao) {
        this.registry = registry;
        this.syncTaskDao = syncTaskDao;
        this.tableMappingDao = tableMappingDao;
        this.triggerDefDao = triggerDefDao;
    }

    /**
     * 部署触发器（幂等）。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     */
    public void install(long taskId, long tableMappingId) {
        SyncTask task = requireTask(taskId);
        TableMapping mapping = requireMapping(taskId, tableMappingId);
        DataSourceHandle source = registry.handle(task.getSourceDsId());
        TriggerDdlGenerator generator = source.dialect().trigger();
        TableMeta table = loadTable(source, mapping);

        TriggerOptions options = TriggerOptions.defaults();
        List<String> ddl = new ArrayList<>();
        ddl.addAll(generator.createChangeLogTableDdl(table, options));
        ddl.addAll(generator.createTriggerDdl(table, options));
        String logTable = generator.changeLogTableName(table.getTableName());
        String triggerNames = triggerNamesOf(table);

        triggerDefDao.upsertPending(taskId, tableMappingId, task.getSourceDsId(), logTable,
                triggerNames, JsonUtil.toJson(ddl));
        try {
            source.withConnection(connection -> {
                executeAll(connection, ddl);
                return Boolean.TRUE;
            });
            triggerDefDao.markDeployed(taskId, tableMappingId);
            log.info("触发器部署完成 task={} mapping={} logTable={} ddl={}", taskId,
                    tableMappingId, logTable, ddl.size());
        } catch (RuntimeException e) {
            triggerDefDao.markFailed(taskId, tableMappingId, e.getMessage());
            throw new DbSyncException(ErrorCode.TRIGGER_DEPLOY_FAILED, e,
                    "触发器部署失败（" + mapping.getSourceTable() + "）：" + e.getMessage());
        }
    }

    /**
     * 卸载触发器（幂等：不存在则忽略）。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     */
    public void uninstall(long taskId, long tableMappingId) {
        SyncTask task = requireTask(taskId);
        TableMapping mapping = requireMapping(taskId, tableMappingId);
        DataSourceHandle source = registry.handle(task.getSourceDsId());
        TableMeta table = loadTable(source, mapping);
        TriggerOptions options = TriggerOptions.defaults();
        List<String> ddl = source.dialect().trigger().dropTriggerDdl(table, options);
        try {
            source.withConnection(connection -> {
                executeAll(connection, ddl);
                return Boolean.TRUE;
            });
        } catch (RuntimeException e) {
            // 卸载失败不阻断（触发器/日志表可能已被人工清掉），只记录
            log.warn("触发器卸载未完全成功 task={} mapping={}：{}", taskId, tableMappingId,
                    e.getMessage());
        }
        if (remainingDeployed(taskId) == 0) {
            triggerDefDao.markAllDropped(taskId);
        }
        log.info("触发器卸载完成 task={} mapping={}", taskId, tableMappingId);
    }

    /**
     * 取已登记的 DDL（供 API 展示/审计与"重放"）。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @return DDL 列表
     */
    public List<String> registeredDdl(long taskId, long tableMappingId) {
        return triggerDefDao.findByTaskAndTable(taskId, tableMappingId)
                .map(def -> {
                    List<String> list = new ArrayList<>();
                    Object parsed = JsonUtil.parse(def.getDdlJson());
                    if (parsed instanceof List) {
                        for (Object item : (List<?>) parsed) {
                            list.add(String.valueOf(item));
                        }
                    }
                    return list;
                })
                .orElseGet(List::of);
    }

    /**
     * 除本表外是否还有 DEPLOYED 的触发器。
     *
     * @param taskId 任务 ID
     * @return 剩余数量
     */
    private long remainingDeployed(long taskId) {
        return triggerDefDao.findByState("DEPLOYED").stream()
                .filter(def -> def.getTaskId() == taskId)
                .count();
    }

    /**
     * 逐条执行 DDL（"已存在"视为成功）。
     *
     * @param connection 连接
     * @param ddl        语句
     * @return true
     * @throws SQLException 非"已存在"错误
     */
    private Boolean executeAll(Connection connection, List<String> ddl) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : ddl) {
                if (sql == null || sql.isBlank()) {
                    continue;
                }
                try {
                    statement.execute(sql);
                } catch (SQLException e) {
                    if (isAlreadyExists(e)) {
                        log.debug("对象已存在，跳过：{}（{}）", firstLine(sql), e.getMessage());
                        continue;
                    }
                    throw e;
                }
            }
        }
        return Boolean.TRUE;
    }

    /**
     * 是否"对象已存在"错误（跨方言识别，避免为每种库写一套判定）。
     *
     * @param e 异常
     * @return true 表示可以忽略
     */
    static boolean isAlreadyExists(SQLException e) {
        String state = e.getSQLState() == null ? "" : e.getSQLState().toUpperCase(Locale.ROOT);
        String message = e.getMessage() == null ? "" : e.getMessage().toUpperCase(Locale.ROOT);
        if ("42S01".equals(state) || "42501X".equals(state) || "X0Y32".equals(state)
                || "42710".equals(state) || "42P07".equals(state)) {
            return true;
        }
        return message.contains("ALREADY EXISTS") || message.contains("ORA-00955")
                || message.contains("ORA-01408") || message.contains("DUPLICATE");
    }

    /**
     * 触发器名清单（台账展示/卸载核对用）。
     *
     * @param table 表元数据
     * @return 逗号分隔名
     */
    private static String triggerNamesOf(TableMeta table) {
        String base = table.getTableName();
        return base + "_SYNC_AI," + base + "_SYNC_AU," + base + "_SYNC_AD";
    }

    /**
     * 读源表元数据。
     *
     * @param source  源句柄
     * @param mapping 映射
     * @return 表元数据
     */
    private TableMeta loadTable(DataSourceHandle source, TableMapping mapping) {
        TableMeta table = source.withConnection(connection -> source.dialect().metadata()
                .loadTable(connection, mapping.getSourceSchema(), mapping.getSourceTable()));
        if (table == null) {
            throw new DbSyncException(ErrorCode.PRECHECK_FAILED,
                    "源表不存在：" + mapping.getSourceTable());
        }
        return table;
    }

    /**
     * 读任务。
     *
     * @param taskId 任务 ID
     * @return 任务
     */
    private SyncTask requireTask(long taskId) {
        return syncTaskDao.findById(taskId)
                .orElseThrow(() -> new DbSyncException(ErrorCode.TASK_NOT_FOUND,
                        "任务不存在：" + taskId));
    }

    /**
     * 读表映射。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 映射 ID
     * @return 映射
     */
    private TableMapping requireMapping(long taskId, long tableMappingId) {
        TableMapping mapping = tableMappingDao.findById(tableMappingId)
                .orElseThrow(() -> new DbSyncException(ErrorCode.PARAM_INVALID,
                        "表映射不存在：" + tableMappingId));
        if (mapping.getTaskId() != taskId) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID,
                    "表映射 " + tableMappingId + " 不属于任务 " + taskId);
        }
        return mapping;
    }

    /**
     * 取语句首行（日志用，避免把多行 DDL 全打出来）。
     *
     * @param sql 语句
     * @return 首行
     */
    private static String firstLine(String sql) {
        int idx = sql.indexOf('\n');
        return idx < 0 ? sql : sql.substring(0, idx);
    }

    /**
     * 供 API 层复用的方言触发器生成器。
     *
     * @param dialect 方言
     * @return 生成器
     */
    static TriggerDdlGenerator generatorOf(Dialect dialect) {
        return dialect.trigger();
    }
}
