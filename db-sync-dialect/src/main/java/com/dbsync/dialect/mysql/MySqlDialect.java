package com.dbsync.dialect.mysql;

import com.dbsync.common.enums.DbType;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.IndexMeta;
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

/**
 * MySQL 8.0 方言。
 *
 * <p>关键特征：反引号引用、{@code ON DUPLICATE KEY UPDATE}、{@code LIMIT ?} 分页、
 * 注释与唯一键内联进 {@code CREATE TABLE}。
 *
 * <p><b>// FIXED</b>：非唯一索引<b>不能</b>内联（MySQL 的 {@code CREATE TABLE} 里
 * {@code KEY/INDEX} 子句与唯一键语义不同，而本方言只内联唯一键），因此非唯一索引
 * 由 {@link com.dbsync.dialect.AbstractDialect#createIndexDdl} 单独发
 * {@code CREATE INDEX}，保证 MAP-03「索引数量与源端一致」。
 */
public final class MySqlDialect extends AbstractDialect implements Dialect {

    private static final Capabilities CAPS = new Capabilities(
            true,    // supportsSkipLocked
            false,   // supportsMerge
            false,   // supportsOnConflict
            true,    // supportsOnDuplicateKey
            false,   // supportsIdentityInsert（直接插自增列即可）
            true,    // supportsIdentityRestart
            false,   // needsFetchSizeCursor（但需 Integer.MIN_VALUE，见 JdbcReader）
            false,   // supportsCommentOn（注释内联）
            true,    // supportsMultiRowValues
            true,    // supportsBatchGeneratedKeys
            true,    // supportsStreamingBlob
            false,   // caseSensitiveIdentifiers
            64,      // maxIdentifierLength
            1000,    // recommendedBatchSize
            "DATABASE()");

    private final MetadataReader metadataReader = new MySqlMetadataReader();
    private final TriggerDdlGenerator triggerGenerator = new MySqlTriggerDdlGenerator();

    @Override
    public DbType type() {
        return DbType.MYSQL;
    }

    @Override
    public String driverClassName() {
        return DbType.MYSQL.driverClassName();
    }

    @Override
    protected char quoteChar() {
        return '`';
    }

    @Override
    protected String uniqueClause(IndexMeta idx, MappingOptions opts) {
        return "UNIQUE KEY " + quoteTarget(idx.getName(), opts) + " ("
                + joinTargetColumns(idx.getColumns(), opts) + ")";
    }

    @Override
    public String mapType(ColumnMeta src, TypeMappingOptions opts) {
        TypeMappingOptions options = opts == null ? TypeMappingOptions.defaults() : opts;
        String t = src.getTypeName() == null ? "VARCHAR" : src.getTypeName().toUpperCase(Locale.ROOT);
        int size = src.getSize();
        int scale = src.getDecimalDigits();
        switch (t) {
            case "NUMBER":
            case "DECIMAL":
            case "NUMERIC":
                if (size <= 0) {
                    // R9：Oracle 无精度 NUMBER 默认 DECIMAL(38,10)
                    return "DECIMAL(" + options.getDefaultNumberPrecision() + ","
                            + options.getDefaultNumberScale() + ")";
                }
                if (scale <= 0 && size <= 19) {
                    return "BIGINT";
                }
                return "DECIMAL(" + size + "," + Math.max(0, scale) + ")";
            case "INT":
            case "INTEGER":
                return "INT";
            case "BIGINT":
                return "BIGINT";
            case "SMALLINT":
                return "SMALLINT";
            case "TINYINT":
                return "TINYINT";
            case "VARCHAR":
            case "VARCHAR2":
            case "NVARCHAR2":
            case "NVARCHAR":
                return "VARCHAR(" + (size > 0 ? size : 255) + ")";
            case "CHAR":
            case "NCHAR":
                return "CHAR(" + (size > 0 ? size : 1) + ")";
            case "CLOB":
            case "NCLOB":
            case "TEXT":
            case "LONGTEXT":
            case "MEDIUMTEXT":
                return "LONGTEXT";
            case "BLOB":
            case "LONGBLOB":
            case "MEDIUMBLOB":
            case "BINARY":
            case "VARBINARY":
                return "LONGBLOB";
            case "DATE":
            case "DATETIME":
            case "TIMESTAMP":
            case "TIMESTAMP WITH TIME ZONE":
            case "TIMESTAMP WITH LOCAL TIME ZONE":
                // 时区差异由归一化 P4 统一处理，此处只保证毫秒精度
                return "DATETIME(3)";
            case "FLOAT":
            case "BINARY_FLOAT":
            case "REAL":
                return "FLOAT";
            case "DOUBLE":
            case "BINARY_DOUBLE":
            case "DOUBLE PRECISION":
                return "DOUBLE";
            case "BOOLEAN":
            case "BIT":
                return "TINYINT";
            default:
                return size > 0 ? t + "(" + size + ")" : t;
        }
    }

    @Override
    protected String buildColumnDef(ColumnMeta col, MappingOptions opts,
                                    Map<String, String> columnOverrides) {
        StringBuilder sb = new StringBuilder();
        sb.append(quoteTarget(col.getName(), opts)).append(' ')
                .append(effectiveType(col, opts, columnOverrides));
        if (col.isUnsigned()) {
            sb.append(" UNSIGNED");
        }
        sb.append(nullabilityAndDefault(col));
        sb.append(buildIdentityClause(col));
        if (opts.isIncludeComments() && col.getComment() != null && !col.getComment().isBlank()) {
            sb.append(" COMMENT ").append(literal(col.getComment()));
        }
        return sb.toString();
    }

    @Override
    protected String buildIdentityClause(ColumnMeta col) {
        return col.isAutoIncrement() ? " AUTO_INCREMENT" : "";
    }

    @Override
    public List<String> commentDdl(TableMeta table, MappingOptions opts) {
        // MySQL 的注释在 CREATE TABLE 内联，无需 COMMENT ON
        return List.of();
    }

    @Override
    public String buildUpsertSql(TableMeta table, List<String> pkCols, List<String> cols) {
        StringBuilder sb = new StringBuilder("INSERT INTO ")
                .append(quote(table.getTableName())).append(" (").append(joinColumns(cols))
                .append(") VALUES (")
                .append(cols.stream().map(c -> "?").collect(java.util.stream.Collectors.joining(", ")))
                .append(")");
        List<String> updates = new java.util.ArrayList<>();
        for (String c : cols) {
            if (!containsIgnoreCase(pkCols, c)) {
                updates.add(quote(c) + "=VALUES(" + quote(c) + ")");
            }
        }
        if (!updates.isEmpty()) {
            sb.append(" ON DUPLICATE KEY UPDATE ").append(String.join(", ", updates));
        }
        return sb.toString();
    }

    @Override
    public String buildPageSql(String baseSql, long offset, int limit) {
        String base = baseSql == null ? "" : baseSql;
        if (base.toUpperCase(Locale.ROOT).contains("ORDER BY")) {
            return "SELECT * FROM (" + base + ") _p LIMIT " + offset + ", " + limit;
        }
        return base + " LIMIT " + offset + ", " + limit;
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
        return CAPS;
    }

    /**
     * 判断列名是否在主键集合中（忽略大小写）。
     *
     * @param cols 主键列
     * @param col  目标列
     * @return true 表示是主键列
     */
    private boolean containsIgnoreCase(List<String> cols, String col) {
        for (String c : cols) {
            if (c.equalsIgnoreCase(col)) {
                return true;
            }
        }
        return false;
    }
}
