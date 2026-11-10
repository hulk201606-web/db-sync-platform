package com.dbsync.dialect.mysql;

import com.dbsync.dialect.JdbcMetadataReader;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * MySQL 元数据读取器：结构信息走 {@link java.sql.DatabaseMetaData}，
 * 行数估算走 {@code information_schema.TABLES.TABLE_ROWS}（架构 §5.2 MySQL 列）。
 */
public final class MySqlMetadataReader extends JdbcMetadataReader {

    /**
     * MySQL 的默认 schema 是当前 database。
     *
     * @return {@code DATABASE()} 的结果
     */
    @Override
    protected String defaultSchema() {
        return null;
    }

    /**
     * MySQL 的 catalog 就是 database：显式 schema 优先，否则取当前连接的 catalog。
     *
     * <p>必须覆盖，否则父类把 schema 传给 {@code getTables} 的 catalog 位、
     * 且默认传 {@code null}，MySQL 驱动会理解为「不限制 catalog」，
     * 把实例里所有库的表都读进来（真库 E2E 实测：MySQL 源库读到了元数据库的 18 张表）。
     *
     * @param c      连接
     * @param schema 调用方传入的 schema（MySQL 语义为 database），可能为空
     * @return 作为 catalog 过滤的库名
     */
    @Override
    protected String catalogOf(Connection c, String schema) {
        if (!isRealMySql(c)) {
            return null;
        }
        if (schema != null && !schema.isBlank()) {
            return schema;
        }
        try {
            return c.getCatalog();
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * MySQL 没有 schema 概念，schemaPattern 恒为 {@code null}（库名走 catalog）。
     *
     * <p>非真实 MySQL 驱动（例如单测用 H2 的 MySQL 兼容模式）回退父类行为：
     * H2 同时支持 catalog 与 schema，按 MySQL 的 catalog 语义传参会读不到表。
     *
     * @param c      连接
     * @param schema 调用方传入的 schema，MySQL 下忽略
     * @return 真实 MySQL 恒为 {@code null}；其它驱动回退父类
     */
    @Override
    protected String schemaPatternOf(Connection c, String schema) {
        if (!isRealMySql(c)) {
            return super.schemaPatternOf(c, schema);
        }
        return null;
    }

    /**
     * 是否为真实 MySQL 驱动。
     *
     * <p>读不到产品名时按 MySQL 处理（宁可走生产语义，也不静默降级）。
     *
     * @param c 连接
     * @return true 表示真实 MySQL
     */
    private static boolean isRealMySql(Connection c) {
        try {
            String product = c.getMetaData().getDatabaseProductName();
            return product != null
                    && product.toLowerCase(java.util.Locale.ROOT).contains("mysql");
        } catch (SQLException e) {
            return true;
        }
    }

    @Override
    public long estimateRows(Connection c, String schema, String table) throws SQLException {
        // 说明：这是"方言隔离"允许的库专属 SQL —— 只出现在 db-sync-dialect 内部
        String sql = "SELECT TABLE_ROWS FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = COALESCE(?, DATABASE()) AND TABLE_NAME = ?";
        return queryLong(c, sql, schema, table);
    }
}
