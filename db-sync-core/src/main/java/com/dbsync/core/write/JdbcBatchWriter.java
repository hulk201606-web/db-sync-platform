package com.dbsync.core.write;

import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.WriteOptions;
import com.dbsync.dialect.Dialect;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 批量写入器（幂等 Upsert / 纯 Insert）。
 *
 * <p><b>五条硬约束</b>（T20）：
 * <ol>
 *   <li>所有值走 {@code PreparedStatement} 参数绑定，<b>绝不</b>拼字符串（SQL 注入防线）；</li>
 *   <li>{@code executeBatch()} 抛 {@code BatchUpdateException} 时<b>整批失败</b>，
 *       不做"部分成功"的猜测——由调用方 ROLLBACK 后用 {@code RetryTemplate} 整批重试（架构 V6）；</li>
 *   <li><b>不 commit</b>：事务归调用方（{@code ShardWorker}），本类只返回
 *       {@link WriteResult#beforeCommit}；只有调用方提交成功后才能构造
 *       {@link WriteResult#afterCommit}；</li>
 *   <li>LOB 列用 {@code setBinaryStream}/{@code setCharacterStream}，不用 {@code setBytes}
 *       （避免把 10MB LOB 复制成 2 份驻留内存）；</li>
 *   <li>Upsert SQL 必须由 {@link Dialect#buildUpsertSql} 生成（方言隔离，硬规则 3）。</li>
 * </ol>
 */
public class JdbcBatchWriter {

    private static final Logger log = LoggerFactory.getLogger(JdbcBatchWriter.class);

    /**
     * 批量幂等 Upsert。
     *
     * @param connection 连接（事务由调用方控制）
     * @param dialect    方言
     * @param table      目标表元数据
     * @param rows       行数据（每行长度必须等于列数）
     * @param opts       写入选项
     * @return 写结果（{@code committed=false}）
     */
    public WriteResult upsert(Connection connection, Dialect dialect, TableMeta table,
                              List<Object[]> rows, WriteOptions opts) {
        WriteOptions options = opts == null ? WriteOptions.upsert() : opts;
        List<String> pkCols = table.primaryKeyNames();
        List<String> cols = table.columnNames();
        String sql = dialect.buildUpsertSql(table, pkCols, cols);
        return execute(connection, dialect, table, rows, options, sql, "UPSERT");
    }

    /**
     * 纯插入（不覆盖）。
     *
     * @param connection 连接
     * @param dialect    方言
     * @param table      目标表元数据
     * @param rows       行数据
     * @param opts       写入选项
     * @return 写结果（{@code committed=false}）
     */
    public WriteResult insert(Connection connection, Dialect dialect, TableMeta table,
                              List<Object[]> rows, WriteOptions opts) {
        WriteOptions options = opts == null ? WriteOptions.insertOnly() : opts;
        List<String> cols = table.columnNames();
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(qualified(dialect, table))
                .append(" (");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append(dialect.quote(cols.get(i)));
        }
        sql.append(") VALUES (");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('?');
        }
        sql.append(')');
        return execute(connection, dialect, table, rows, options, sql.toString(), "INSERT");
    }

    /**
     * 执行批量绑定 + executeBatch。
     *
     * @param connection 连接
     * @param dialect    方言
     * @param table      表元数据
     * @param rows       行数据
     * @param options    写入选项
     * @param sql        SQL
     * @param action     动作名（日志用）
     * @return 写结果
     */
    private WriteResult execute(Connection connection, Dialect dialect, TableMeta table,
                                List<Object[]> rows, WriteOptions options, String sql,
                                String action) {
        if (rows == null || rows.isEmpty()) {
            return WriteResult.beforeCommit(0L, 0L);
        }
        List<ColumnMeta> columns = table.getColumns();
        int expected = columns.size();
        long start = System.currentTimeMillis();
        PreparedStatement statement = null;
        try {
            statement = connection.prepareStatement(sql);
            for (int r = 0; r < rows.size(); r++) {
                Object[] row = rows.get(r);
                if (row == null || row.length != expected) {
                    throw new DbSyncException(ErrorCode.PARAM_INVALID,
                            "第 " + r + " 行列数 " + (row == null ? 0 : row.length)
                                    + " 与表列数 " + expected + " 不一致");
                }
                bindRow(statement, columns, row, options);
                statement.addBatch();
            }
            statement.executeBatch();
            return WriteResult.beforeCommit(rows.size(), System.currentTimeMillis() - start);
        } catch (SQLException e) {
            // 硬约束 2：整批失败，不做部分成功的猜测
            throw new DbSyncException(ErrorCode.CHANGE_APPLY_FAILED,
                    action + " 批量失败（整批将回滚后重试）：" + e.getMessage())
                    .with("table", table.qualifiedName()).with("rows", rows.size())
                    .with("sql", sql);
        } finally {
            closeQuietly(statement);
        }
    }

    /**
     * 绑定一行。
     *
     * @param statement 语句
     * @param columns   列元数据
     * @param row       行数据
     * @param options   写入选项
     * @throws SQLException 绑定失败
     */
    private void bindRow(PreparedStatement statement, List<ColumnMeta> columns, Object[] row,
                         WriteOptions options) throws SQLException {
        for (int i = 0; i < columns.size(); i++) {
            ColumnMeta column = columns.get(i);
            Object value = row[i];
            int index = i + 1;
            if (value == null) {
                statement.setNull(index, column.getJdbcType() > 0 ? column.getJdbcType()
                        : Types.NULL);
                continue;
            }
            if (options.isStreamLob() && column.isLob()) {
                bindLob(statement, index, value);
                continue;
            }
            statement.setObject(index, value);
        }
    }

    /**
     * 以流的方式绑定 LOB。
     *
     * @param statement 语句
     * @param index     参数序号
     * @param value     值（byte[] 或 String）
     * @throws SQLException 绑定失败
     */
    private void bindLob(PreparedStatement statement, int index, Object value)
            throws SQLException {
        if (value instanceof byte[]) {
            byte[] data = (byte[]) value;
            statement.setBinaryStream(index, new ByteArrayInputStream(data), data.length);
            return;
        }
        if (value instanceof String) {
            String text = (String) value;
            statement.setCharacterStream(index, new StringReader(text), text.length());
            return;
        }
        statement.setObject(index, value);
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
        if (schema == null || schema.isEmpty()) {
            return dialect.quote(table.getTableName());
        }
        return dialect.quote(schema) + "." + dialect.quote(table.getTableName());
    }

    /**
     * 关闭语句（异常只记日志，不掩盖真实失败原因）。
     *
     * @param statement 语句
     */
    private static void closeQuietly(PreparedStatement statement) {
        if (statement == null) {
            return;
        }
        try {
            statement.close();
        } catch (SQLException e) {
            log.debug("关闭写语句失败（忽略）：{}", e.getMessage());
        }
    }
}
