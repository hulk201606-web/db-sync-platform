package com.dbsync.dialect;

import com.dbsync.common.enums.IdentifierCase;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.IndexMeta;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.MappingOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 方言实现模板（架构 §5.1）：把"引号转义、大小写归一化、CREATE TABLE 组装、索引/注释生成"
 * 这些**与具体库无关的骨架**收敛到这里，子类只需实现真正有差异的 4 个钩子。
 *
 * <p>子类必须无状态（可单例）。
 *
 * <p><b>// FIXED(MAP-02)</b>：{@code identifierCase} 此前只被持久化/回显，
 * {@link #normalize} 在全项目生产代码零调用，DDL 里所有标识符仍原样 {@code quote}。
 * 现在所有写入<b>目标端 DDL</b> 的标识符在 quote 前统一走
 * {@link #quoteTarget(String, MappingOptions)}（表名 / 列名 / schema / 索引名 / 约束名）。
 * 注意：从源库读取元数据用的原始列名<b>不</b>在此改动——本类只处理拼进 DDL 的标识符，
 * {@code TableMeta} 里的列名保持源端原样，源表查询/取样不受影响。
 *
 * <p><b>// FIXED(P2)</b>：列类型覆盖由「DDL 文本替换」改为「结构化生成」——
 * 覆盖表按列名传入方言，{@code buildColumnDef} 直接采用覆盖类型；
 * 索引/注释 DDL 走同一覆盖上下文，覆盖成 LOB/TEXT 时跳过不可索引的索引。
 */
public abstract class AbstractDialect implements Dialect {

    private static final Logger log = LoggerFactory.getLogger(AbstractDialect.class);

    /**
     * @return 引号字符（MySQL：{@code `}；其余：{@code "}）
     */
    protected abstract char quoteChar();

    /**
     * @return 是否支持 {@code CREATE TABLE IF NOT EXISTS}
     */
    protected boolean supportsIfNotExists() {
        return true;
    }

    /**
     * 单列定义（类型 + 约束），结构化版：接收列类型覆盖表。
     *
     * @param col             列元数据
     * @param opts            映射选项
     * @param columnOverrides 列类型覆盖表（列名大小写不敏感 → 覆盖类型）
     * @return 形如 {@code `id` BIGINT NOT NULL}
     */
    protected abstract String buildColumnDef(ColumnMeta col, MappingOptions opts,
                                             Map<String, String> columnOverrides);

    /**
     * 单列定义（无覆盖），保留旧签名以兼容既有子类/调用方。
     *
     * @param col  列元数据
     * @param opts 映射选项
     * @return 列定义
     */
    protected String buildColumnDef(ColumnMeta col, MappingOptions opts) {
        return buildColumnDef(col, opts, Map.of());
    }

    /**
     * 自增/标识子句（子类按需覆盖）。
     *
     * @param col 列元数据
     * @return 子句（可为空串）
     */
    protected String buildIdentityClause(ColumnMeta col) {
        return "";
    }

    /**
     * 唯一键子句（结构化版，目标标识符按 {@code opts} 归一化）。
     *
     * @param idx  唯一索引
     * @param opts 映射选项
     * @return 形如 {@code CONSTRAINT "uk_x" UNIQUE ("a", "b")}
     */
    protected String uniqueClause(IndexMeta idx, MappingOptions opts) {
        return "CONSTRAINT " + quoteTarget(idx.getName(), opts) + " UNIQUE ("
                + joinTargetColumns(idx.getColumns(), opts) + ")";
    }

    /**
     * 唯一键子句（无 opts，默认 PRESERVE，保留旧签名兼容）。
     *
     * @param idx 唯一索引
     * @return 子句
     */
    protected String uniqueClause(IndexMeta idx) {
        return uniqueClause(idx, MappingOptions.defaults());
    }

    /**
     * @return CREATE TABLE 尾部选项（默认空）
     */
    protected String tableOptionsSuffix() {
        return "";
    }

    @Override
    public String quote(String identifier) {
        if (identifier == null) {
            return null;
        }
        char q = quoteChar();
        StringBuilder sb = new StringBuilder(identifier.length() + 2);
        sb.append(q);
        for (int i = 0; i < identifier.length(); i++) {
            char c = identifier.charAt(i);
            if (c == q) {
                sb.append(q);
            }
            sb.append(c);
        }
        sb.append(q);
        return sb.toString();
    }

    @Override
    public String normalize(String identifier, IdentifierCase strategy) {
        if (identifier == null || strategy == null || strategy == IdentifierCase.PRESERVE) {
            return identifier;
        }
        return strategy == IdentifierCase.UPPER
                ? identifier.toUpperCase(Locale.ROOT)
                : identifier.toLowerCase(Locale.ROOT);
    }

    /**
     * 目标端标识符统一入口：按 {@link MappingOptions#getIdentifierCase()} 归一化后再加引号。
     *
     * <p>这是 MAP-02 的核心落点——所有拼进目标 DDL 的标识符（表名、列名、schema、
     * 索引名、约束名、主键列名）都必须经此方法，而非裸 {@link #quote(String)}。
     * 裸 {@code quote} 仍供运行时 SQL（upsert / count / range predicate 等）使用，
     * 那些语句拿不到 {@code MappingOptions}、且用的是源端原始列名，不在本次归一化范围。
     *
     * @param identifier 目标标识符（源端原始大小写）
     * @param opts       映射选项（null 视为 PRESERVE）
     * @return 归一化并加引号后的标识符
     */
    protected String quoteTarget(String identifier, MappingOptions opts) {
        IdentifierCase strategy = opts == null ? IdentifierCase.PRESERVE : opts.getIdentifierCase();
        return quote(normalize(identifier, strategy));
    }

    /**
     * 目标端列名列表：逐个 {@link #quoteTarget} 后逗号连接。
     *
     * @param columns 列名（源端原始大小写）
     * @param opts    映射选项
     * @return 片段
     */
    protected String joinTargetColumns(List<String> columns, MappingOptions opts) {
        return columns.stream().map(c -> quoteTarget(c, opts))
                .collect(Collectors.joining(", "));
    }

    /**
     * 生效目标类型：人工覆盖优先（按列名，大小写不敏感），否则自动 {@link #mapType}。
     *
     * <p>覆盖只认列名，与源列「当前」类型、生成映射时的类型快照都无关，
     * 从根上消除了旧文本替换「源类型一变 needle 就失配」的缺陷。
     *
     * @param col             列元数据
     * @param opts            映射选项
     * @param columnOverrides 列类型覆盖表
     * @return 目标类型串
     */
    protected String effectiveType(ColumnMeta col, MappingOptions opts,
                                   Map<String, String> columnOverrides) {
        String override = overrideFor(col.getName(), columnOverrides);
        return override != null ? override : mapType(col, opts.getTypeMapping());
    }

    /**
     * 按列名（大小写不敏感）取覆盖类型。兼容「键已是全大写」与「键为原始大小写」两种入参。
     *
     * @param columnName      列名
     * @param columnOverrides 覆盖表
     * @return 覆盖类型（trim 后非空），无覆盖返回 null
     */
    private String overrideFor(String columnName, Map<String, String> columnOverrides) {
        if (columnOverrides == null || columnOverrides.isEmpty() || columnName == null) {
            return null;
        }
        String value = columnOverrides.get(columnName.toUpperCase(Locale.ROOT));
        if (value == null) {
            for (Map.Entry<String, String> e : columnOverrides.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(columnName)) {
                    value = e.getValue();
                    break;
                }
            }
        }
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 是否「不可索引」类型（CLOB/BLOB/TEXT/BYTEA/LONGTEXT/LONGBLOB/NCLOB 等）。
     *
     * @param type 类型串（覆盖类型或自动映射类型）
     * @return true 表示该类型不能作为索引列
     */
    protected boolean isNonIndexableType(String type) {
        if (type == null) {
            return false;
        }
        String t = type.toUpperCase(Locale.ROOT);
        return t.contains("CLOB") || t.contains("BLOB") || t.contains("TEXT")
                || t.contains("BYTEA");
    }

    /**
     * 索引是否含「不可索引」列（覆盖类型或自动映射类型）。
     *
     * @param table           表元数据（用于按列名取自动映射类型）
     * @param idx             索引
     * @param opts            映射选项
     * @param columnOverrides 列类型覆盖表
     * @return true 表示该索引不应生成
     */
    protected boolean indexHasNonIndexableColumn(TableMeta table, IndexMeta idx,
            MappingOptions opts, Map<String, String> columnOverrides) {
        for (String colName : idx.getColumns()) {
            String type = overrideFor(colName, columnOverrides);
            if (type == null) {
                ColumnMeta col = table.column(colName).orElse(null);
                type = col == null ? null : mapType(col, opts.getTypeMapping());
            }
            if (isNonIndexableType(type)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String createTableDdl(TableMeta table, MappingOptions opts) {
        return createTableDdl(table, opts, Map.of());
    }

    @Override
    public String createTableDdl(TableMeta table, MappingOptions opts,
                                 Map<String, String> columnOverrides) {
        MappingOptions options = opts == null ? MappingOptions.defaults() : opts;
        Map<String, String> overrides = columnOverrides == null ? Map.of() : columnOverrides;
        StringBuilder sb = new StringBuilder("CREATE TABLE ");
        if (supportsIfNotExists()) {
            sb.append("IF NOT EXISTS ");
        }
        sb.append(qualifiedName(table, options)).append(" (");
        List<String> parts = new ArrayList<>();
        for (ColumnMeta col : table.getColumns()) {
            parts.add(buildColumnDef(col, options, overrides));
        }
        if (table.hasPrimaryKey()) {
            parts.add("PRIMARY KEY (" + joinTargetColumns(table.primaryKeyNames(), options) + ")");
        }
        if (options.isIncludeUniqueKeys()) {
            for (IndexMeta idx : table.getIndexes()) {
                if (idx.isUnique()) {
                    if (indexHasNonIndexableColumn(table, idx, options, overrides)) {
                        log.warn("跳过唯一键 DDL：唯一键 {} 含覆盖/映射后不可索引的列类型",
                                idx.getName());
                        continue;
                    }
                    parts.add(uniqueClause(idx, options));
                }
            }
        }
        sb.append(String.join(", ", parts)).append(")").append(tableOptionsSuffix());
        return sb.toString();
    }

    /**
     * 建索引 DDL：<b>只</b>输出非唯一索引。
     *
     * <p>为什么唯一键不在这里输出：唯一键已经内联在 {@code CREATE TABLE} 里
     * （{@link #uniqueClause}，MySQL 走 {@code UNIQUE KEY}，其它方言走
     * {@code CONSTRAINT .. UNIQUE}），再单独发一条 {@code CREATE UNIQUE INDEX}
     * 会因"索引名重复"或"约束同名"在目标库直接失败。
     *
     * <p><b>// FIXED</b>：本方法原先有一个 {@code indexInlineInCreateTable()} 开关，
     * MySQL 打开后<b>整个</b>方法返回空——但 {@code CREATE TABLE} 只内联了唯一键，
     * 非唯一索引因此被静默丢弃，导致 MAP-03「索引数量与源端一致」在 MySQL 目标上不成立，
     * 并连带让 MAP-05 的 {@code ddl-diff} 把所有索引判成 {@code MISSING}。
     * 现在所有方言（含 MySQL）都按需输出非唯一索引的 {@code CREATE INDEX}。
     *
     * <p><b>// FIXED(P2)</b>：索引列被覆盖成 LOB/TEXT 后跳过该索引并 WARN，
     * 不再产出必然执行失败的 {@code CREATE INDEX}。
     */
    @Override
    public List<String> createIndexDdl(TableMeta table, MappingOptions opts) {
        return createIndexDdl(table, opts, Map.of());
    }

    @Override
    public List<String> createIndexDdl(TableMeta table, MappingOptions opts,
                                       Map<String, String> columnOverrides) {
        MappingOptions options = opts == null ? MappingOptions.defaults() : opts;
        Map<String, String> overrides = columnOverrides == null ? Map.of() : columnOverrides;
        if (!options.isIncludeIndexes()) {
            return List.of();
        }
        List<String> ddl = new ArrayList<>();
        for (IndexMeta idx : table.getIndexes()) {
            if (idx.isUnique()) {
                continue;
            }
            if (indexHasNonIndexableColumn(table, idx, options, overrides)) {
                log.warn("跳过索引 DDL：索引 {} 含覆盖/映射后不可索引的列类型（LOB/TEXT）",
                        idx.getName());
                continue;
            }
            ddl.add("CREATE INDEX " + quoteTarget(idx.getName(), options) + " ON "
                    + qualifiedName(table, options) + " ("
                    + joinTargetColumns(idx.getColumns(), options) + ")");
        }
        return List.copyOf(ddl);
    }

    @Override
    public List<String> commentDdl(TableMeta table, MappingOptions opts) {
        MappingOptions options = opts == null ? MappingOptions.defaults() : opts;
        if (!options.isIncludeComments()) {
            return List.of();
        }
        List<String> ddl = new ArrayList<>();
        if (table.getComment() != null && !table.getComment().isBlank()) {
            ddl.add("COMMENT ON TABLE " + qualifiedName(table, options) + " IS "
                    + literal(table.getComment()));
        }
        for (ColumnMeta col : table.getColumns()) {
            if (col.getComment() != null && !col.getComment().isBlank()) {
                ddl.add("COMMENT ON COLUMN " + qualifiedName(table, options) + "."
                        + quoteTarget(col.getName(), options) + " IS " + literal(col.getComment()));
            }
        }
        return List.copyOf(ddl);
    }

    @Override
    public List<String> commentDdl(TableMeta table, MappingOptions opts,
                                   Map<String, String> columnOverrides) {
        // 注释语句与列类型无关，覆盖不改变 COMMENT ON；委托 2-arg 以尊重子类（MySQL 返回空）。
        return commentDdl(table, opts);
    }

    /**
     * 全限定名（schema 存在时加引号），目标标识符按 {@code opts} 归一化。
     *
     * @param table 表元数据
     * @param opts  映射选项
     * @return {@code "schema"."table"} 或 {@code "table"}
     */
    protected String qualifiedName(TableMeta table, MappingOptions opts) {
        if (table.getSchema() == null || table.getSchema().isBlank()) {
            return quoteTarget(table.getTableName(), opts);
        }
        return quoteTarget(table.getSchema(), opts) + "."
                + quoteTarget(table.getTableName(), opts);
    }

    /**
     * 全限定名（默认 PRESERVE，保留旧签名兼容）。
     *
     * @param table 表元数据
     * @return 片段
     */
    protected String qualifiedName(TableMeta table) {
        return qualifiedName(table, MappingOptions.defaults());
    }

    /**
     * 单引号字面量（转义内部单引号）。
     *
     * @param raw 原串
     * @return {@code 'escaped'}
     */
    protected String literal(String raw) {
        return "'" + raw.replace("'", "''") + "'";
    }

    /**
     * 列名列表加引号后以逗号连接（运行时 SQL 用，不归一化）。
     *
     * @param columns 列名
     * @return 片段
     */
    protected String joinColumns(List<String> columns) {
        return columns.stream().map(this::quote).collect(Collectors.joining(", "));
    }

    @Override
    public String buildRangePredicate(String quotedPkCol, boolean upperBoundInclusive) {
        return quotedPkCol + (upperBoundInclusive ? " <= ?" : " < ?");
    }

    @Override
    public String buildJdbcUrl(com.dbsync.common.model.JdbcEndpoint endpoint) {
        String base = type().urlPrefix() + endpoint.getHost() + ":" + endpoint.getPort()
                + (endpoint.getDatabase() == null || endpoint.getDatabase().isBlank()
                        ? "" : "/" + endpoint.getDatabase());
        String params = endpoint.paramsQuery();
        return params.isEmpty() ? base : base + "?" + params;
    }

    /**
     * 数值/字符/LOB 的通用可空性与默认值尾部。
     *
     * @param col 列元数据
     * @return 形如 {@code " NOT NULL"} 或 {@code " DEFAULT 0"}
     */
    protected String nullabilityAndDefault(ColumnMeta col) {
        StringBuilder sb = new StringBuilder();
        if (!col.isNullable()) {
            sb.append(" NOT NULL");
        }
        if (col.getDefaultValue() != null && !col.getDefaultValue().isBlank()) {
            sb.append(" DEFAULT ").append(col.getDefaultValue());
        }
        return sb.toString();
    }
}
