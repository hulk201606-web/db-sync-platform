package com.dbsync.cdc;

import com.dbsync.common.enums.ChangeOp;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.ChangeEvent;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.WriteOptions;
import com.dbsync.core.write.JdbcBatchWriter;
import com.dbsync.core.write.WriteResult;
import com.dbsync.dialect.Dialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单条变更应用器。
 *
 * <p><b>为什么单条而不是批量</b>：增量要保序（组内串行），
 * 批内并行处理会让同一主键的两条变更互相覆盖（后写的未必是后发生的）。
 * 单条写入的性能靠"每批一个事务 + 目标库的 prepared 语句缓存"来补，
 * 而不是靠批内并行。
 *
 * <p><b>为什么复用 {@link JdbcBatchWriter}</b>：它已经把 {@code setNull(jdbcType)}、
 * LOB 流式绑定、{@code BatchUpdateException} 整批失败语义都做对了；
 * upsert SQL 也由 {@link Dialect#buildUpsertSql} 生成（硬规则 3）。
 * 在这里重写一遍绑定逻辑，只会造出第二套"看起来一样但边界不同"的实现。
 */
public class UpsertApplier {

    private static final Logger log = LoggerFactory.getLogger(UpsertApplier.class);

    private final JdbcBatchWriter writer = new JdbcBatchWriter();

    /**
     * 应用一条变更。
     *
     * <p>事务由调用方控制：本方法<b>不</b> commit / rollback。
     *
     * @param connection 目标连接
     * @param dialect    目标方言
     * @param table      目标表元数据
     * @param e          变更事件
     * @return 应用结果
     */
    public ApplyResult apply(Connection connection, Dialect dialect, TableMeta table,
                             ChangeEvent e) {
        if (e.getOp() == ChangeOp.DELETE) {
            return delete(connection, dialect, table, e);
        }
        Map<String, Object> values = e.getAfter();
        if (values == null || values.isEmpty()) {
            throw new DbSyncException(ErrorCode.CHANGE_APPLY_FAILED,
                    "变更 " + e.getId() + " 的 after 镜像为空，无法应用 " + e.getOp());
        }
        Object[] row = new Object[table.getColumns().size()];
        List<ColumnMeta> columns = table.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            row[i] = lookup(values, columns.get(i).getName());
        }
        List<Object[]> rows = new ArrayList<>(1);
        rows.add(row);
        WriteResult written = writer.upsert(connection, dialect, table, rows,
                WriteOptions.upsert());
        log.debug("应用 {} 变更 id={} rows={}", e.getOp(), e.getId(), written.rowsAffected());
        return ApplyResult.applied((int) written.rowsAffected());
    }

    /**
     * 删除目标行。
     *
     * @param connection 目标连接
     * @param dialect    目标方言
     * @param table      目标表
     * @param e          变更事件
     * @return 应用结果
     */
    private ApplyResult delete(Connection connection, Dialect dialect, TableMeta table,
                               ChangeEvent e) {
        List<String> pks = table.primaryKeyNames();
        if (pks.isEmpty()) {
            throw new DbSyncException(ErrorCode.CHANGE_APPLY_FAILED,
                    "目标表 " + table.getTableName() + " 无主键，无法按主键删除");
        }
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(qualified(dialect, table))
                .append(" WHERE ");
        for (int i = 0; i < pks.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            sql.append(dialect.quote(pks.get(i))).append("=?");
        }
        List<Object> pkTuple = e.getPkTuple();
        if (pkTuple == null || pkTuple.size() != pks.size()) {
            throw new DbSyncException(ErrorCode.CHANGE_APPLY_FAILED,
                    "变更 " + e.getId() + " 的主键元组与表主键列数不一致");
        }
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < pks.size(); i++) {
                ps.setObject(i + 1, pkTuple.get(i));
            }
            int rows = ps.executeUpdate();
            // 删除 0 行不是错误：目标端本来就没有（幂等重放/重复消息都会这样）
            log.debug("应用 DELETE 变更 id={} rows={}", e.getId(), rows);
            return ApplyResult.applied(rows);
        } catch (SQLException ex) {
            throw new DbSyncException(ErrorCode.CHANGE_APPLY_FAILED, ex,
                    "删除目标行失败（" + table.getTableName() + "）：" + ex.getMessage());
        }
    }

    /**
     * 取列值（大小写不敏感，兼容不同驱动返回的镜像键名）。
     *
     * @param values 镜像
     * @param column 列名
     * @return 值；不存在返回 null
     */
    private static Object lookup(Map<String, Object> values, String column) {
        if (values.containsKey(column)) {
            return values.get(column);
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getKey() != null
                    && entry.getKey().toLowerCase(Locale.ROOT).equals(column.toLowerCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 拼引号全名。
     *
     * @param dialect 方言
     * @param table   表元数据
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
