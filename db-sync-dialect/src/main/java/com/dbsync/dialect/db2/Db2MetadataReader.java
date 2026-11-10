package com.dbsync.dialect.db2;

import com.dbsync.dialect.JdbcMetadataReader;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * DB2 LUW 元数据读取器：结构走 JDBC 标准元数据，行数估算走 {@code SYSCAT.TABLES.CARD}。
 */
public final class Db2MetadataReader extends JdbcMetadataReader {

    @Override
    protected String defaultSchema() {
        return null;
    }

    @Override
    public long estimateRows(Connection c, String schema, String table) throws SQLException {
        String sql = "SELECT CARD FROM SYSCAT.TABLES WHERE TABNAME = ? "
                + "AND TABSCHEMA = COALESCE(?, CURRENT SCHEMA)";
        return queryLong(c, sql, table, schema);
    }
}
