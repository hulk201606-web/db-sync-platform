package com.dbsync.core.read;

import com.dbsync.common.enums.DbType;
import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.PkRange;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.ReadOptions;
import com.dbsync.common.util.PkRangeUtil;
import com.dbsync.core.datasource.DataSourceHandle;
import com.dbsync.dialect.Dialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 流式区间读取器 —— 全平台<b>风险最高</b>的一段代码（架构 R2）。
 *
 * <p><b>为什么必须流式</b>：单表最大 8 亿行，驱动默认行为是把结果集全部拉进内存，
 * 1 亿行 × 200 字节 ≈ 20GB，直接 OOM。三个库的"开流式"姿势完全不同，
 * 少做一步就静默退化成全量加载（不报错，只是某天 OOM）：
 * <table border="1">
 *   <caption>流式三坑（架构 §5.5）</caption>
 *   <tr><th>库</th><th>必须做的事</th><th>不做的后果</th></tr>
 *   <tr><td>Oracle / DM</td><td>{@code setAutoCommit(false)} + {@code setFetchSize(≥500)}</td><td>全量进内存</td></tr>
 *   <tr><td>MySQL</td><td>{@code TYPE_FORWARD_ONLY} + {@code setFetchSize(Integer.MIN_VALUE)}</td><td>全量进内存</td></tr>
 *   <tr><td>PostgreSQL</td><td>{@code setAutoCommit(false)} + {@code setFetchSize(>0)}</td><td>全量进内存</td></tr>
 *   <tr><td>DB2</td><td>{@code setFetchSize(n)}</td><td>常规生效</td></tr>
 * </table>
 *
 * <p>另有：含 LOB 列时由调用方传 {@code ReadOptions.lobSafe()}（batchSize=1），
 * 避免"一批 1000 行 × 每行 10MB LOB"的瞬时内存峰值。
 *
 * <p><b>连接归属</b>：本类<b>不</b>关闭 Connection（连接归池），只保证
 * ResultSet/Statement 在 finally 里关闭——即使回调抛异常。
 */
public class JdbcReader {

    private static final Logger log = LoggerFactory.getLogger(JdbcReader.class);

    /** 游标模式下 fetchSize 的下限（太小会导致 Oracle 频繁往返，太大占内存）。 */
    private static final int MIN_CURSOR_FETCH_SIZE = 500;

    private final ResultSetRowMapper rowMapper = new ResultSetRowMapper();

    /**
     * 流式读取区间（借还连接由本方法负责）。
     *
     * @param handle 数据源句柄
     * @param table  表元数据
     * @param range  区间（语义 {@code (start, end]}，null 端表示无界）
     * @param opts   读取选项
     * @param cb     行回调
     * @return 读取行数
     */
    public long read(DataSourceHandle handle, TableMeta table, PkRange range, ReadOptions opts,
                     RowCallback cb) {
        Connection connection = null;
        try {
            connection = handle.connection();
            return read(connection, handle.dialect(), table, range, opts, cb);
        } catch (SQLException e) {
            throw new DbSyncException(ErrorCode.CONN_NETWORK_FAILED,
                    "流式读取失败：" + e.getMessage()).with("table", table.qualifiedName())
                    .with("dsId", handle.id());
        } finally {
            closeQuietly(connection);
        }
    }

    /**
     * 流式读取区间（连接由调用方提供，<b>不会</b>被关闭）。
     *
     * <p>// ADDED: 04 文档只给出 handle 版本；补连接版本是为了能在单测里
     * 用 mock Connection 断言 setAutoCommit/setFetchSize/ResultSetType（T19 验收 1/2/5）。
     *
     * @param connection 连接
     * @param dialect    方言
     * @param table      表元数据
     * @param range      区间
     * @param opts       读取选项
     * @param cb         行回调
     * @return 读取行数
     */
    public long read(Connection connection, Dialect dialect, TableMeta table, PkRange range,
                     ReadOptions opts, RowCallback cb) {
        ReadOptions options = opts == null ? ReadOptions.of(1000) : opts;
        List<ColumnMeta> columns = table.getColumns();
        String sql = buildSql(dialect, table, range);
        int fetchSize = resolveFetchSize(dialect, options, connection);

        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            statement = prepare(connection, dialect, sql, fetchSize);
            int index = bindRange(statement, dialect, table, range);
            resultSet = statement.executeQuery();
            long rowIndex = 0L;
            while (resultSet.next()) {
                cb.onRow(rowMapper.map(resultSet, columns), rowIndex);
                rowIndex++;
            }
            return rowIndex;
        } catch (SQLException e) {
            throw new DbSyncException(ErrorCode.CONN_NETWORK_FAILED,
                    "流式读取失败（" + table.qualifiedName() + "）：" + e.getMessage())
                    .with("sql", sql);
        } finally {
            closeResultSet(resultSet);
            closeStatement(statement);
            // 连接不关：归还由调用方/池负责（架构 §5.5）
        }
    }

    /**
     * 建语句：按方言决定 ResultSetType 与 fetchSize（流式三坑的唯一实现点）。
     *
     * @param connection 连接
     * @param dialect    方言
     * @param sql        SQL
     * @param fetchSize  fetchSize
     * @return 已配置好的语句
     * @throws SQLException 建语句失败
     */
    private PreparedStatement prepare(Connection connection, Dialect dialect, String sql,
                                      int fetchSize) throws SQLException {
        DbType type = dialect.type();
        boolean cursorMode = needsCursorMode(dialect);
        if (cursorMode) {
            // Oracle/PG：只有非自动提交 + fetchSize>0 才是"游标读"
            if (isAutoCommit(connection)) {
                connection.setAutoCommit(false);
            }
        }
        PreparedStatement statement;
        if (type == DbType.MYSQL) {
            // MySQL 唯一正确的流式姿势：FORWARD_ONLY + Integer.MIN_VALUE
            statement = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY);
        } else {
            statement = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY);
        }
        statement.setFetchSize(fetchSize);
        return statement;
    }

    /**
     * 是否需要"游标模式"（非自动提交）。
     *
     * @param dialect 方言
     * @return true 表示需要
     */
    private static boolean needsCursorMode(Dialect dialect) {
        if (dialect.capabilities() != null && dialect.capabilities().needsFetchSizeCursor()) {
            return true;
        }
        DbType type = dialect.type();
        return type == DbType.ORACLE || type == DbType.DM || type == DbType.POSTGRESQL;
    }

    /**
     * 计算 fetchSize。
     *
     * @param dialect 方言
     * @param options 读取选项
     * @return fetchSize
     */
    private static int resolveFetchSize(Dialect dialect, ReadOptions options, Connection connection) {
        if (dialect.type() == DbType.MYSQL) {
            int requested = options.getFetchSize() > 0 ? options.getFetchSize()
                    : options.getBatchSize();
            if (isH2(connection)) {
                // H2 在 MySQL 模式下会拒绝 Integer.MIN_VALUE（Invalid value for parameter "rows"）；
                // H2 只用于单测/演示，这里退化为普通 fetchSize，生产 MySQL 仍走魔数流式
                return Math.max(1, requested);
            }
            // 魔法值：MySQL 用 Integer.MIN_VALUE 表示"逐行流式"
            return Integer.MIN_VALUE;
        }
        int requested = options.getFetchSize() > 0 ? options.getFetchSize() : options.getBatchSize();
        if (needsCursorMode(dialect)) {
            return Math.max(MIN_CURSOR_FETCH_SIZE, requested);
        }
        return Math.max(1, requested);
    }

    /**
     * 拼区间查询 SQL。
     *
     * @param dialect 方言
     * @param table   表元数据
     * @param range   区间
     * @return SQL
     */
    private String buildSql(Dialect dialect, TableMeta table, PkRange range) {
        StringBuilder sql = new StringBuilder("SELECT ");
        List<ColumnMeta> columns = table.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append(dialect.quote(columns.get(i).getName()));
        }
        sql.append(" FROM ").append(qualified(dialect, table));

        String pk = table.hasPrimaryKey() ? table.primaryKeyColumns().get(0).getName() : null;
        boolean hasLower = range != null && range.getStart() != null && pk != null;
        boolean hasUpper = range != null && range.getEnd() != null && pk != null;
        if (hasLower || hasUpper) {
            sql.append(" WHERE ");
            boolean needAnd = false;
            if (hasLower) {
                sql.append(dialect.buildLowerPredicate(dialect.quote(pk)));
                needAnd = true;
            }
            if (hasUpper) {
                if (needAnd) {
                    sql.append(" AND ");
                }
                sql.append(dialect.buildRangePredicate(dialect.quote(pk), true));
            }
        }
        if (pk != null) {
            sql.append(" ORDER BY ").append(dialect.quote(pk));
        }
        return sql.toString();
    }

    /**
     * 绑定区间参数。
     *
     * @param statement 语句
     * @param dialect   方言
     * @param table     表元数据
     * @param range     区间
     * @return 下一个可用的参数序号
     * @throws SQLException 绑定失败
     */
    private int bindRange(PreparedStatement statement, Dialect dialect, TableMeta table,
                          PkRange range) throws SQLException {
        if (range == null || !table.hasPrimaryKey()) {
            return 1;
        }
        RangeKind kind = range.getKind();
        int index = 1;
        if (range.getStart() != null) {
            bindValue(statement, index++, range.getStart(), kind);
        }
        if (range.getEnd() != null) {
            bindValue(statement, index++, range.getEnd(), kind);
        }
        return index;
    }

    /**
     * 绑定单个边界值（数值统一转 BigDecimal，避免驱动把字符串当文本比较）。
     *
     * @param statement 语句
     * @param index     参数序号
     * @param value     值
     * @param kind      区间类型
     * @throws SQLException 绑定失败
     */
    private void bindValue(PreparedStatement statement, int index, Object value, RangeKind kind)
            throws SQLException {
        Object typed = value;
        if (kind == RangeKind.NUMERIC && !(value instanceof Number)) {
            try {
                typed = PkRangeUtil.parse(String.valueOf(value), RangeKind.NUMERIC);
            } catch (RuntimeException e) {
                typed = value;
            }
        }
        statement.setObject(index, typed);
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
     * 当前连接是否指向 H2（仅单测/演示环境会出现）。
     *
     * @param connection 连接
     * @return true 表示 H2
     */
    private static boolean isH2(Connection connection) {
        try {
            java.sql.DatabaseMetaData metaData = connection.getMetaData();
            if (metaData == null) {
                return false;
            }
            String product = metaData.getDatabaseProductName();
            return product != null && product.toUpperCase(java.util.Locale.ROOT).contains("H2");
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 读 autoCommit（部分驱动/代理会抛异常，此时按 true 处理以便执行补偿）。
     *
     * @param connection 连接
     * @return autoCommit
     */
    private static boolean isAutoCommit(Connection connection) {
        try {
            return connection.getAutoCommit();
        } catch (SQLException e) {
            log.debug("读取 autoCommit 失败，按 true 处理：{}", e.getMessage());
            return true;
        }
    }

    /**
     * 关闭 ResultSet（异常只记日志）。
     *
     * @param rs 结果集
     */
    private static void closeResultSet(ResultSet rs) {
        if (rs == null) {
            return;
        }
        try {
            rs.close();
        } catch (SQLException e) {
            log.debug("关闭 ResultSet 失败（忽略）：{}", e.getMessage());
        }
    }

    /**
     * 关闭 Statement（异常只记日志）。
     *
     * @param statement 语句
     */
    private static void closeStatement(PreparedStatement statement) {
        if (statement == null) {
            return;
        }
        try {
            statement.close();
        } catch (SQLException e) {
            log.debug("关闭 Statement 失败（忽略）：{}", e.getMessage());
        }
    }

    /**
     * 归还连接（异常只记日志）。
     *
     * @param connection 连接
     */
    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.debug("回滚只读事务失败（忽略）：{}", e.getMessage());
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("归还连接失败（忽略）：{}", e.getMessage());
        }
    }
}
