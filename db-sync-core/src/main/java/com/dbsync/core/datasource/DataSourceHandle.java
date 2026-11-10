package com.dbsync.core.datasource;

import com.dbsync.common.enums.DbType;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.model.DataSourceDef;
import com.dbsync.dialect.Dialect;
import com.dbsync.dialect.DialectRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * 数据源句柄：一个已建好的连接池 + 其方言。
 *
 * <p>生命周期：由 {@link DataSourceRegistry} 创建与关闭；
 * 业务代码只借 {@link #connection()} 或用 {@link #withConnection(SqlFunction)}，
 * <b>不得</b>关闭池。
 * <p>本类是 {@code final}（不允许被继承改写 autoCommit 语义）。
 */
public final class DataSourceHandle implements AutoCloseable {

    private final DataSourceDef def;
    private final Dialect dialect;
    private final DataSource dataSource;
    private final ConnectionTester tester;
    private volatile boolean closed;

    /**
     * 构造。
     *
     * @param def        数据源定义
     * @param dataSource 连接池
     * @param tester     连接探测器（供 {@link #test()} 复用）
     */
    DataSourceHandle(DataSourceDef def, DataSource dataSource, ConnectionTester tester) {
        this.def = def;
        this.dialect = DialectRegistry.get(def.getDbType());
        this.dataSource = dataSource;
        this.tester = tester;
    }

    /** @return 数据源 ID */
    public long id() {
        return def.getId();
    }

    /** @return 数据库类型 */
    public DbType dbType() {
        return def.getDbType();
    }

    /** @return 方言 */
    public Dialect dialect() {
        return dialect;
    }

    /** @return 数据源定义（只读用途，含口令密文，禁止打日志） */
    public DataSourceDef definition() {
        return def;
    }

    /** @return 底层连接池（仅供需要直接调用的适配器使用） */
    public DataSource dataSource() {
        return dataSource;
    }

    /** @return 是否已关闭 */
    public boolean isClosed() {
        return closed;
    }

    /**
     * 借一个连接（调用方负责关闭）。
     *
     * @return 连接
     * @throws SQLException 借连接失败
     */
    public Connection connection() throws SQLException {
        if (closed) {
            throw new DbSyncException(ErrorCode.DATASOURCE_CONFIG_INVALID,
                    "连接池已关闭：" + def.getName()).with("dsId", def.getId());
        }
        return dataSource.getConnection();
    }

    /**
     * 在连接上执行并自动关闭连接。
     *
     * <p>不捕获异常：SQL 异常原样抛给调用方（由各引擎决定分类与重试）。
     *
     * @param fn  执行函数
     * @param <T> 返回类型
     * @return 结果
     */
    public <T> T withConnection(SqlFunction<T> fn) {
        Connection connection = null;
        try {
            connection = connection();
            return fn.apply(connection);
        } catch (SQLException e) {
            throw new DbSyncException(ErrorCode.CONN_NETWORK_FAILED,
                    "数据库操作失败：" + e.getMessage()).with("dsId", def.getId());
        } finally {
            closeQuietly(connection);
        }
    }

    /**
     * 探测当前数据源连通性（走独立短连接，不占用池）。
     *
     * @return 探测结果
     */
    public ConnectionTestResult test() {
        return tester.test(def);
    }

    /**
     * 关闭连接池。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (dataSource instanceof AutoCloseable) {
            try {
                ((AutoCloseable) dataSource).close();
            } catch (Exception e) {
                throw new DbSyncException(ErrorCode.INTERNAL_ERROR,
                        "关闭连接池失败：" + e.getMessage()).with("dsId", def.getId());
            }
        }
    }

    /**
     * 静默关闭连接。
     *
     * @param connection 连接
     */
    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 归还连接失败不影响调用方；池会自行淘汰坏连接
        }
    }

    @Override
    public String toString() {
        return "DataSourceHandle#" + def.getId() + "[" + def.getDbType() + "," + def.getName() + "]";
    }
}
