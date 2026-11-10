package com.dbsync.dialect.postgresql;

import com.dbsync.dialect.JdbcMetadataReader;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * PostgreSQL 元数据读取器：结构走 JDBC 标准元数据，行数估算走 {@code pg_class.reltuples}。
 */
public final class PostgresMetadataReader extends JdbcMetadataReader {

    @Override
    protected String defaultSchema() {
        return "public";
    }

    @Override
    public long estimateRows(Connection c, String schema, String table) throws SQLException {
        String sql = "SELECT reltuples::bigint FROM pg_catalog.pg_class c "
                + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE c.relname = ? AND n.nspname = COALESCE(?, current_schema())";
        return queryLong(c, sql, table, schema);
    }
}
