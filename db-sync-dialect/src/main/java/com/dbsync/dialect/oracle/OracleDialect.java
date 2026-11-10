package com.dbsync.dialect.oracle;

import com.dbsync.common.enums.DbType;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.MappingOptions;
import com.dbsync.common.options.TypeMappingOptions;
import com.dbsync.dialect.AbstractDialect;
import com.dbsync.dialect.Capabilities;
import com.dbsync.dialect.Dialect;
import com.dbsync.dialect.MetadataReader;
import com.dbsync.dialect.TriggerDdlGenerator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Oracle 11g/19c 方言。
 *
 * <p>最关键的一条能力：{@code needsFetchSizeCursor() == true} —— 只有在
 * {@code Connection.setAutoCommit(false)} 下 {@code setFetchSize} 才会生效为服务器端游标，
 * 否则驱动会把整个结果集读进内存（8 亿行必 OOM，架构 R2 / 坑 K2）。
 */
public class OracleDialect extends AbstractDialect implements Dialect {

    /** 分页语法模式（11g 用 ROWNUM，12c+ 可用 FETCH FIRST）。 */
    public enum Pagination {
        /** 11g 兼容：ROWNUM 双层包装。 */
        ROWNUM,
        /** 12c+：{@code OFFSET ... FETCH NEXT}。 */
        FETCH_FIRST
    }

    private final Pagination pagination;
    private final int maxIdentifierLength;
    private final MetadataReader metadataReader;
    private final TriggerDdlGenerator triggerGenerator;
    private final Capabilities capabilities;

    /**
     * 默认构造：11g 兼容（ROWNUM + 30 字符标识符）。
     */
    public OracleDialect() {
        this(Pagination.ROWNUM, 30);
    }

    /**
     * 构造。
     *
     * @param pagination          分页语法
     * @param maxIdentifierLength 标识符上限（11g=30，12c+=128）
     */
    public OracleDialect(Pagination pagination, int maxIdentifierLength) {
        this.pagination = pagination == null ? Pagination.ROWNUM : pagination;
        this.maxIdentifierLength = maxIdentifierLength <= 0 ? 30 : maxIdentifierLength;
        this.metadataReader = new OracleMetadataReader();
        this.triggerGenerator = new OracleTriggerDdlGenerator();
        this.capabilities = new Capabilities(
                false,  // supportsSkipLocked（11g 不支持）
                true,   // supportsMerge
                false,  // supportsOnConflict
                false,  // supportsOnDuplicateKey
                false,  // supportsIdentityInsert
                false,  // supportsIdentityRestart
                true,   // needsFetchSizeCursor ★
                true,   // supportsCommentOn
                false,  // supportsMultiRowValues（11g 不支持多值 INSERT）
                false,  // supportsBatchGeneratedKeys
                true,   // supportsStreamingBlob
                true,   // caseSensitiveIdentifiers
                this.maxIdentifierLength,
                500,    // recommendedBatchSize
                "SYS_CONTEXT('USERENV','CURRENT_SCHEMA')");
    }

    @Override
    public DbType type() {
        return DbType.ORACLE;
    }

    @Override
    public String driverClassName() {
        return DbType.ORACLE.driverClassName();
    }

    @Override
    protected char quoteChar() {
        return '"';
    }

    @Override
    protected boolean supportsIfNotExists() {
        // Oracle 没有 CREATE TABLE IF NOT EXISTS
        return false;
    }

    @Override
    public String currentTimestampExpr() {
        return "SYSTIMESTAMP";
    }

    @Override
    public String mapType(ColumnMeta src, TypeMappingOptions opts) {
        TypeMappingOptions options = opts == null ? TypeMappingOptions.defaults() : opts;
        String t = src.getTypeName() == null ? "VARCHAR2" : src.getTypeName().toUpperCase(Locale.ROOT);
        int size = src.getSize();
        int scale = src.getDecimalDigits();
        switch (t) {
            case "NUMBER":
            case "DECIMAL":
            case "NUMERIC":
                if (size <= 0) {
                    // R9：Oracle 无精度 NUMBER → NUMBER(38,10)
                    return "NUMBER(" + options.getDefaultNumberPrecision() + ","
                            + options.getDefaultNumberScale() + ")";
                }
                if (scale <= 0) {
                    return "NUMBER(" + size + ")";
                }
                return "NUMBER(" + size + "," + scale + ")";
            case "INT":
            case "INTEGER":
            case "BIGINT":
            case "SMALLINT":
            case "TINYINT":
                return "NUMBER(" + (size > 0 ? size : 19) + ")";
            case "VARCHAR":
            case "VARCHAR2":
            case "NVARCHAR2":
            case "NVARCHAR":
                return "VARCHAR2(" + (size > 0 ? size : 255) + ")";
            case "CHAR":
            case "NCHAR":
                return "CHAR(" + (size > 0 ? size : 1) + ")";
            case "TEXT":
            case "LONGTEXT":
            case "CLOB":
            case "NCLOB":
                return "CLOB";
            case "BLOB":
            case "LONGBLOB":
            case "BYTEA":
                return "BLOB";
            case "DATE":
                return "DATE";
            case "DATETIME":
            case "TIMESTAMP":
            case "TIMESTAMP WITH TIME ZONE":
                return "TIMESTAMP(6)";
            case "FLOAT":
            case "BINARY_FLOAT":
                return "BINARY_FLOAT";
            case "DOUBLE":
            case "BINARY_DOUBLE":
            case "DOUBLE PRECISION":
                return "BINARY_DOUBLE";
            case "BOOLEAN":
            case "BIT":
                return "NUMBER(1)";
            default:
                return size > 0 ? t + "(" + size + ")" : t;
        }
    }

    @Override
    protected String buildColumnDef(ColumnMeta col, MappingOptions opts,
                                    Map<String, String> columnOverrides) {
        return quoteTarget(col.getName(), opts) + " "
                + effectiveType(col, opts, columnOverrides)
                + nullabilityAndDefault(col);
    }

    @Override
    public String buildUpsertSql(TableMeta table, List<String> pkCols, List<String> cols) {
        String selectList = cols.stream().map(c -> "? AS " + quote(c))
                .collect(Collectors.joining(", "));
        String on = pkCols.stream().map(c -> "tgt." + quote(c) + " = src." + quote(c))
                .collect(Collectors.joining(" AND "));
        List<String> updates = cols.stream().filter(c -> !containsIgnoreCase(pkCols, c))
                .map(c -> "tgt." + quote(c) + " = src." + quote(c))
                .collect(Collectors.toList());
        StringBuilder sb = new StringBuilder("MERGE INTO ").append(quote(table.getTableName()))
                .append(" tgt USING (SELECT ").append(selectList).append(" FROM DUAL) src ON (")
                .append(on).append(")");
        if (!updates.isEmpty()) {
            sb.append(" WHEN MATCHED THEN UPDATE SET ").append(String.join(", ", updates));
        }
        sb.append(" WHEN NOT MATCHED THEN INSERT (").append(joinColumns(cols))
                .append(") VALUES (")
                .append(cols.stream().map(c -> "src." + quote(c)).collect(Collectors.joining(", ")))
                .append(")");
        return sb.toString();
    }

    @Override
    public String buildPageSql(String baseSql, long offset, int limit) {
        String base = baseSql == null ? "" : baseSql;
        if (pagination == Pagination.FETCH_FIRST) {
            return base + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
        }
        long upper = offset + limit;
        return "SELECT * FROM (SELECT a.*, ROWNUM rn FROM (" + base
                + ") a WHERE ROWNUM <= " + upper + ") WHERE rn > " + offset;
    }

    @Override
    public MetadataReader metadata() {
        return metadataReader;
    }

    @Override
    public TriggerDdlGenerator trigger() {
        return triggerGenerator;
    }

    @Override
    public Capabilities capabilities() {
        return capabilities;
    }

    /**
     * @return 当前分页模式
     */
    public Pagination pagination() {
        return pagination;
    }

    private boolean containsIgnoreCase(List<String> cols, String col) {
        for (String c : cols) {
            if (c.equalsIgnoreCase(col)) {
                return true;
            }
        }
        return false;
    }
}
