package com.dbsync.dialect.oracle;

import com.dbsync.dialect.JdbcMetadataReader;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Oracle 元数据读取器：结构走 JDBC 标准元数据，行数估算走 {@code ALL_TABLES.NUM_ROWS}（架构 §5.2）。
 */
public class OracleMetadataReader extends JdbcMetadataReader {

    @Override
    protected String defaultSchema() {
        return null;
    }

    @Override
    public long estimateRows(Connection c, String schema, String table) throws SQLException {
        String sql = "SELECT NUM_ROWS FROM ALL_TABLES WHERE OWNER = NVL(?, SYS_CONTEXT('USERENV','CURRENT_SCHEMA')) "
                + "AND TABLE_NAME = ?";
        return queryLong(c, sql, schema, table);
    }
}
