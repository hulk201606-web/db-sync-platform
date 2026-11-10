package com.dbsync.migration;

import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import com.dbsync.core.datasource.DataSourceHandle;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.dialect.Dialect;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.TableMappingDao;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 行数核数器（迁移收尾的"粗筛"，PRD MIG-08）。
 *
 * <p><b>为什么先做行数再做逐行比对</b>：行数不一致是"确定有问题"，
 * 而且代价极低（一次索引全扫）；逐行校验和是重活（要读两边全表），
 * 先用行数把"明显不对"的表挑出来，能省掉 90% 的无效比对。
 *
 * <p><b>局限（必须在报告里写清）</b>：行数相同不等于内容相同
 * （少 1 行 A、多 1 行 B 时行数也相同）。真正的正确性判定在
 * {@code db-sync-compare} 的校验和比对，本类只负责"数量对不对"。
 */
public class RowCountVerifier {

    private static final Logger log = LoggerFactory.getLogger(RowCountVerifier.class);

    private final SyncTaskDao syncTaskDao;
    private final TableMappingDao tableMappingDao;
    private final DataSourceRegistry registry;

    /**
     * 构造。
     *
     * @param syncTaskDao     任务 DAO
     * @param tableMappingDao 表映射 DAO
     * @param registry        数据源注册表
     */
    public RowCountVerifier(SyncTaskDao syncTaskDao, TableMappingDao tableMappingDao,
                            DataSourceRegistry registry) {
        this.syncTaskDao = syncTaskDao;
        this.tableMappingDao = tableMappingDao;
        this.registry = registry;
    }

    /**
     * 构造（不依赖数据源注册表，仅供核数核心逻辑复用/单测）。
     *
     * <p>// ADDED: {@code DataSourceHandle} 是 final 类、必须由注册表构造，
     * 因此"给定两侧连接"的核心比对被抽成独立入口，让 T26 的验收
     * （少插 5 行 → 差异 5）能直接在 H2 上跑真实 SQL。
     *
     * @param syncTaskDao     任务 DAO
     * @param tableMappingDao 表映射 DAO
     */
    RowCountVerifier(SyncTaskDao syncTaskDao, TableMappingDao tableMappingDao) {
        this(syncTaskDao, tableMappingDao, null);
    }

    /**
     * 逐表比对源/目标行数。
     *
     * @param taskId 任务 ID
     * @return 差异列表；<b>空列表</b>表示全部一致
     */
    public List<RowCountDiff> verify(long taskId) {
        SyncTask task = syncTaskDao.findById(taskId)
                .orElseThrow(() -> new DbSyncException(ErrorCode.TASK_NOT_FOUND,
                        "任务不存在：" + taskId));
        List<TableMapping> mappings = tableMappingDao.findEnabledByTask(taskId);
        if (mappings.isEmpty()) {
            return List.of();
        }
        if (registry == null) {
            throw new DbSyncException(ErrorCode.DATASOURCE_CONFIG_INVALID,
                    "未配置数据源注册表，无法执行行数核数");
        }
        DataSourceHandle source = registry.handle(task.getSourceDsId());
        DataSourceHandle target = registry.handle(task.getTargetDsId());
        return source.withConnection(sourceConn ->
                target.withConnection(targetConn -> compareAll(mappings, sourceConn,
                        source.dialect(), targetConn, target.dialect())));
    }

    /**
     * 核数核心（给定两侧连接，逐表 COUNT 比对）。
     *
     * @param mappings      表映射
     * @param sourceConn    源连接
     * @param sourceDialect 源方言
     * @param targetConn    目标连接
     * @param targetDialect 目标方言
     * @return 差异列表（只含不一致的表）
     */
    List<RowCountDiff> compareAll(List<TableMapping> mappings, Connection sourceConn,
                                  Dialect sourceDialect, Connection targetConn,
                                  Dialect targetDialect) {
        List<RowCountDiff> diffs = new ArrayList<>();
        for (TableMapping mapping : mappings) {
            long sourceRows = count(sourceConn, sourceDialect, mapping.getSourceSchema(),
                    mapping.getSourceTable());
            long targetRows = count(targetConn, targetDialect, mapping.getTargetSchema(),
                    mapping.getTargetTable());
            if (sourceRows != targetRows) {
                RowCountDiff diff = new RowCountDiff(mapping.getId(),
                        readable(mapping.getSourceSchema(), mapping.getSourceTable()),
                        readable(mapping.getTargetSchema(), mapping.getTargetTable()),
                        sourceRows, targetRows);
                diffs.add(diff);
                log.warn("行数不一致 {}", diff);
            } else {
                log.debug("行数一致 {}={}", mapping.getSourceTable(), sourceRows);
            }
        }
        return diffs;
    }

    /**
     * 统计单表行数。
     *
     * @param connection 连接
     * @param dialect    方言
     * @param schema     schema（可空）
     * @param table      表名
     * @return 行数
     */
    long count(Connection connection, Dialect dialect, String schema, String table) {
        String sql = "SELECT COUNT(*) FROM " + qualified(dialect, schema, table);
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new DbSyncException(ErrorCode.CONN_NETWORK_FAILED, e,
                    "统计行数失败（" + readable(schema, table) + "）：" + e.getMessage());
        }
    }

    /**
     * 拼引号全名（标识符一律走方言 quote，硬规则 8）。
     *
     * @param dialect 方言
     * @param schema  schema（可空）
     * @param table   表名
     * @return 全名
     */
    static String qualified(Dialect dialect, String schema, String table) {
        if (schema == null || schema.isBlank()) {
            return dialect.quote(table);
        }
        return dialect.quote(schema) + "." + dialect.quote(table);
    }

    /**
     * 拼人类可读全名（仅用于报告，不参与 SQL）。
     *
     * @param schema schema（可空）
     * @param table  表名
     * @return 全名
     */
    static String readable(String schema, String table) {
        return schema == null || schema.isBlank() ? table : schema + "." + table;
    }
}
