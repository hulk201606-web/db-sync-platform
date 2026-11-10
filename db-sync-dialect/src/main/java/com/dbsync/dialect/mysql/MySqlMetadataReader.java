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

    @Override
    public long estimateRows(Connection c, String schema, String table) throws SQLException {
        // 说明：这是"方言隔离"允许的库专属 SQL —— 只出现在 db-sync-dialect 内部
        String sql = "SELECT TABLE_ROWS FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = COALESCE(?, DATABASE()) AND TABLE_NAME = ?";
        return queryLong(c, sql, schema, table);
    }
}
