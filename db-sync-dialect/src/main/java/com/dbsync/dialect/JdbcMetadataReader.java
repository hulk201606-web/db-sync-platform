// ADDED: 04 文档只列了 5 个 *MetadataReader 实现，本类把"与库无关"的 JDBC 标准元数据读取
// （DatabaseMetaData：列/主键/索引/注释/自增）抽成共享基类，各方言只覆盖 estimateRows（系统视图统计）。
// 理由：@see <a href="file:docs/02-architecture.md">架构 §5.2</a> 的 5 套系统视图 SQL 中，
// 列/主键/索引的结构信息用 DatabaseMetaData 已能跨 5 种库拿到一致结果，重复写 5 遍 SQL 只会放大维护成本与偏差风险。
package com.dbsync.dialect;

import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.IndexMeta;
import com.dbsync.common.model.PrimaryKeyMeta;
import com.dbsync.common.model.TableMeta;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 基于 {@link DatabaseMetaData} 的通用元数据读取器。
 *
 * <p>子类只需实现 {@link #estimateRows}（方言统计视图）与 {@link #defaultSchema}。
 */
public abstract class JdbcMetadataReader implements MetadataReader {

    /**
     * @return 方言默认 schema 表达式结果（用于未指定 schema 时）
     */
    protected abstract String defaultSchema();

    /**
     * {@link DatabaseMetaData} 的 catalog 参数。
     *
     * <p>默认返回 {@code null}（不按 catalog 过滤）。MySQL 的 catalog 就是 database，
     * 由 {@code MySqlMetadataReader} 覆盖为「显式 schema 优先，否则取当前连接的 catalog」——
     * 否则 {@code getTables(null, schema, ...)} 会被 MySQL 驱动当成「不限制 catalog」，
     * 把实例里所有库的表都读进来（2026-11-10 真库 E2E 实测：MySQL 源库读到元数据库的表）。
     *
     * @param c      连接
     * @param schema 调用方传入的 schema，可能为空
     * @return catalog 过滤值，{@code null} 表示不限制
     */
    protected String catalogOf(Connection c, String schema) {
        return null;
    }

    /**
     * {@link DatabaseMetaData} 的 schemaPattern 参数。
     *
     * <p>默认「显式 schema 优先，否则方言默认 schema」；MySQL 无 schema 概念，恒返回 {@code null}。
     *
     * @param c      连接（供方言判断驱动类型）
     * @param schema 调用方传入的 schema，可能为空
     * @return schemaPattern 过滤值，{@code null} 表示不限制
     */
    protected String schemaPatternOf(Connection c, String schema) {
        return (schema == null || schema.isBlank()) ? defaultSchema() : schema;
    }

    @Override
    public List<String> listSchemas(Connection c) throws SQLException {
        List<String> schemas = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getSchemas()) {
            while (rs.next()) {
                schemas.add(rs.getString("TABLE_SCHEM"));
            }
        }
        return List.copyOf(schemas);
    }

    @Override
    public List<String> listTables(Connection c, String schema) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getTables(catalogOf(c, schema),
                schemaPatternOf(c, schema), "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
        }
        return List.copyOf(tables);
    }

    @Override
    public TableMeta loadTable(Connection c, String schema, String table) throws SQLException {
        List<ColumnMeta> columns = loadColumns(c, schema, table);
        PrimaryKeyMeta pk = loadPrimaryKey(c, schema, table);
        List<IndexMeta> indexes = loadIndexes(c, schema, table);
        long estimate = -1L;
        try {
            estimate = estimateRows(c, schema, table);
        } catch (SQLException e) {
            // 统计信息不可用不阻塞迁移：调用方降级为抽样 COUNT
            estimate = -1L;
        }
        String comment = tableComment(c, schema, table);
        return new TableMeta(schema == null || schema.isBlank() ? defaultSchema() : schema,
                table, columns, pk, indexes, estimate, comment);
    }

    @Override
    public List<ColumnMeta> loadColumns(Connection c, String schema, String table) throws SQLException {
        List<ColumnMeta> columns = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getColumns(catalogOf(c, schema),
                schemaPatternOf(c, schema), table, "%")) {
            while (rs.next()) {
                String typeName = rs.getString("TYPE_NAME");
                int jdbcType = rs.getInt("DATA_TYPE");
                ColumnMeta.Builder b = ColumnMeta.builder(rs.getString("COLUMN_NAME"),
                                rs.getInt("ORDINAL_POSITION"), typeName)
                        .jdbcType(jdbcType)
                        .size(rs.getInt("COLUMN_SIZE"))
                        .decimalDigits(rs.getInt("DECIMAL_DIGITS"))
                        .nullable(!"NO".equalsIgnoreCase(rs.getString("IS_NULLABLE")))
                        .defaultValue(rs.getString("COLUMN_DEF"))
                        .comment(rs.getString("REMARKS"))
                        .autoIncrement("YES".equalsIgnoreCase(safeString(rs, "IS_AUTOINCREMENT")))
                        .unsigned(typeName != null
                                && typeName.toUpperCase(java.util.Locale.ROOT).contains("UNSIGNED"))
                        .lob(isLobType(jdbcType, typeName));
                columns.add(b.build());
            }
        }
        return List.copyOf(columns);
    }

    @Override
    public PrimaryKeyMeta loadPrimaryKey(Connection c, String schema, String table) throws SQLException {
        Map<Short, String> ordered = new TreeMap<>();
        String pkName = null;
        try (ResultSet rs = c.getMetaData().getPrimaryKeys(catalogOf(c, schema),
                schemaPatternOf(c, schema), table)) {
            while (rs.next()) {
                ordered.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
                pkName = rs.getString("PK_NAME");
            }
        }
        if (ordered.isEmpty()) {
            return PrimaryKeyMeta.NONE;
        }
        return new PrimaryKeyMeta(pkName, new ArrayList<>(ordered.values()));
    }

    @Override
    public List<IndexMeta> loadIndexes(Connection c, String schema, String table) throws SQLException {
        Map<String, Map<Short, String>> byName = new LinkedHashMap<>();
        Map<String, Boolean> unique = new LinkedHashMap<>();
        try (ResultSet rs = c.getMetaData().getIndexInfo(catalogOf(c, schema),
                schemaPatternOf(c, schema), table, false, false)) {
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                if (indexName == null) {
                    continue;
                }
                byName.computeIfAbsent(indexName, k -> new TreeMap<>())
                        .put(rs.getShort("ORDINAL_POSITION"), rs.getString("COLUMN_NAME"));
                unique.put(indexName, !rs.getBoolean("NON_UNIQUE"));
            }
        }
        List<IndexMeta> indexes = new ArrayList<>();
        for (Map.Entry<String, Map<Short, String>> e : byName.entrySet()) {
            indexes.add(new IndexMeta(e.getKey(), new ArrayList<>(e.getValue().values()),
                    Boolean.TRUE.equals(unique.get(e.getKey()))));
        }
        return List.copyOf(indexes);
    }

    /**
     * 读取表注释（DatabaseMetaData 的 REMARKS 在部分库为空，子类可覆盖为系统视图查询）。
     *
     * @param c      连接
     * @param schema schema
     * @param table  表名
     * @return 注释，可能为 null
     * @throws SQLException 读取失败
     */
    protected String tableComment(Connection c, String schema, String table) throws SQLException {
        try (ResultSet rs = c.getMetaData().getTables(catalogOf(c, schema),
                schemaPatternOf(c, schema), table, new String[] {"TABLE"})) {
            return rs.next() ? rs.getString("REMARKS") : null;
        }
    }

    /**
     * 是否大字段类型。
     *
     * @param jdbcType JDBC 类型码
     * @param typeName 类型名
     * @return true 表示 LOB
     */
    private static boolean isLobType(int jdbcType, String typeName) {
        if (jdbcType == Types.CLOB || jdbcType == Types.BLOB || jdbcType == Types.NCLOB
                || jdbcType == Types.LONGVARCHAR || jdbcType == Types.LONGVARBINARY
                || jdbcType == Types.SQLXML) {
            return true;
        }
        if (typeName == null) {
            return false;
        }
        String t = typeName.toUpperCase(java.util.Locale.ROOT);
        return t.contains("TEXT") || t.contains("CLOB") || t.contains("BLOB") || t.contains("BYTEA")
                || t.contains("IMAGE") || t.contains("XML");
    }

    /**
     * 安全取列（列不存在时返回 null，兼容不同驱动）。
     *
     * @param rs     结果集
     * @param column 列名
     * @return 值或 null
     */
    protected static String safeString(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * 执行单值 long 查询（供子类实现 estimateRows）。
     *
     * @param c   连接
     * @param sql SQL（只含已校验的 schema/表名参数绑定）
     * @param args 绑定参数
     * @return 结果；无行返回 -1
     * @throws SQLException 执行失败
     */
    protected long queryLong(Connection c, String sql, Object... args) throws SQLException {
        try (java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    return rs.wasNull() ? -1L : v;
                }
            }
        }
        return -1L;
    }

    /**
     * 执行 DDL（自动提交模式下）。
     *
     * @param c   连接
     * @param sql DDL
     * @throws SQLException 执行失败
     */
    protected void executeDdl(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
