package com.dbsync.dialect;

import com.dbsync.common.enums.DbType;
import com.dbsync.common.enums.IdentifierCase;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.JdbcEndpoint;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.MappingOptions;
import com.dbsync.common.options.TypeMappingOptions;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据库方言 SPI（架构 §5.1，契约唯一来源）。
 *
 * <p><b>硬约束</b>：
 * <ul>
 *   <li>所有跨库 SQL 差异必须收敛到实现类内部；上层引擎（migration/cdc/compare/cutover）
 *       禁止出现任何库专属字面量（{@code LIMIT} / {@code ROWNUM} / {@code MERGE INTO} /
 *       {@code ON CONFLICT} / {@code FETCH FIRST} / {@code SYSIBM} 等）；</li>
 *   <li>实现类必须<b>无状态</b>（可单例复用），不得持有 {@code Connection}；</li>
 *   <li>本接口只接受 {@link TableMeta} / 已校验的标识符，<b>不接受</b>原始用户字符串
 *       （SQL 注入防线，架构 §12.2）。</li>
 * </ul>
 */
public interface Dialect {

    // ---------- 身份与连接 ----------

    /**
     * @return 数据库类型
     */
    DbType type();

    /**
     * @return JDBC 驱动类全名（用于反射加载，见 {@link JdbcDriverLoader}）
     */
    String driverClassName();

    /**
     * 由端点拼 JDBC URL。
     *
     * @param endpoint 端点（host/port/database/schema/params）
     * @return JDBC URL
     */
    String buildJdbcUrl(JdbcEndpoint endpoint);

    // ---------- 标识符 ----------

    /**
     * 加引号并转义内部引号（例如 MySQL 的 {@code `a`b`} → {@code `a``b`}）。
     *
     * @param identifier 已校验的标识符
     * @return 加引号后的标识符
     */
    String quote(String identifier);

    /**
     * 归一化标识符大小写（必须用 {@link java.util.Locale#ROOT}）。
     *
     * @param identifier 标识符
     * @param strategy   策略
     * @return 归一化结果
     */
    String normalize(String identifier, IdentifierCase strategy);

    // ---------- 类型映射与 DDL ----------

    /**
     * 源列类型 → 目标类型串。
     *
     * @param src  源列元数据
     * @param opts 类型映射选项
     * @return 目标类型串（如 {@code DECIMAL(38,10)}）
     */
    String mapType(ColumnMeta src, TypeMappingOptions opts);

    /**
     * 建表 DDL。
     *
     * @param table 表元数据
     * @param opts  映射选项
     * @return 单条 {@code CREATE TABLE} 语句
     */
    String createTableDdl(TableMeta table, MappingOptions opts);

    /**
     * 建索引 DDL（<b>只</b>非唯一索引；唯一键已内联在 {@link #createTableDdl} 里）。
     *
     * @param table 表元数据
     * @param opts  映射选项
     * @return 语句列表（{@code includeIndexes=false} 或表无非唯一索引时为空）
     */
    List<String> createIndexDdl(TableMeta table, MappingOptions opts);

    /**
     * 注释 DDL。
     *
     * @param table 表元数据
     * @param opts  映射选项
     * @return 语句列表（MySQL 返回空，注释已内联）
     */
    List<String> commentDdl(TableMeta table, MappingOptions opts);

    // ---------- 结构化列类型覆盖（default 实现，保持接口向后兼容） ----------

    /**
     * 建表 DDL（结构化列类型覆盖版）。
     *
     * <p>覆盖表以「目标列名（大小写不敏感）→ 覆盖后类型」传入，方言在
     * {@code buildColumnDef} 里直接采用覆盖类型，避免对已生成的 DDL 文本做替换。
     * 默认实现回退到 {@link #createTableDdl(TableMeta, MappingOptions)}，
     * 仅为兼容未实现该重载的第三方 {@code Dialect}；本仓 5 种方言均覆盖此方法。
     *
     * @param table           表元数据
     * @param opts            映射选项
     * @param columnOverrides 列类型覆盖表（可 null / 空）
     * @return 单条 {@code CREATE TABLE} 语句
     */
    default String createTableDdl(TableMeta table, MappingOptions opts,
                                  Map<String, String> columnOverrides) {
        return createTableDdl(table, opts);
    }

    /**
     * 建索引 DDL（结构化列类型覆盖版）：覆盖成 LOB/TEXT 等不可索引类型时跳过该索引。
     *
     * @param table           表元数据
     * @param opts            映射选项
     * @param columnOverrides 列类型覆盖表（可 null / 空）
     * @return 语句列表
     */
    default List<String> createIndexDdl(TableMeta table, MappingOptions opts,
                                        Map<String, String> columnOverrides) {
        return createIndexDdl(table, opts);
    }

    /**
     * 注释 DDL（结构化列类型覆盖版）。注释语句与列类型无关，覆盖不改变输出；
     * 默认委托 2-arg 以尊重子类（如 MySQL 注释内联、返回空）。
     *
     * @param table           表元数据
     * @param opts            映射选项
     * @param columnOverrides 列类型覆盖表（可 null / 空）
     * @return 语句列表
     */
    default List<String> commentDdl(TableMeta table, MappingOptions opts,
                                    Map<String, String> columnOverrides) {
        return commentDdl(table, opts);
    }

    // ---------- SQL 片段生成 ----------

    /**
     * 幂等 Upsert 语句。
     *
     * @param table 表元数据
     * @param pkCols 主键列名（已校验，未加引号）
     * @param cols   全部列名（已校验，未加引号，顺序与绑定参数一致）
     * @return SQL，参数按 {@code cols} 顺序绑定
     */
    String buildUpsertSql(TableMeta table, List<String> pkCols, List<String> cols);

    /**
     * 分页语句。
     *
     * @param baseSql 基础查询
     * @param offset  偏移
     * @param limit   条数
     * @return 分页 SQL
     */
    String buildPageSql(String baseSql, long offset, int limit);

    /**
     * 上界谓词片段（下界固定为 {@code pk > ?}）。
     *
     * @param quotedPkCol       已加引号的主键列
     * @param upperBoundInclusive 是否包含上界
     * @return 形如 {@code `id` <= ?} 或 {@code `id` < ?}
     */
    String buildRangePredicate(String quotedPkCol, boolean upperBoundInclusive);

    // ---------- 能力扩展 ----------

    /**
     * @return 元数据读取器
     */
    MetadataReader metadata();

    /**
     * @return 触发器/变更日志 DDL 生成器
     */
    TriggerDdlGenerator trigger();

    /**
     * @return 能力声明
     */
    Capabilities capabilities();

    // ---------- 可选补充（default 实现，允许方言覆盖） ----------

    /**
     * 下界谓词（固定左开）。
     *
     * @param quotedPkCol 已加引号的主键列
     * @return 形如 {@code "id" > ?}
     */
    default String buildLowerPredicate(String quotedPkCol) {
        return quotedPkCol + " > ?";
    }

    /**
     * @return 当前时间表达式
     */
    default String currentTimestampExpr() {
        return "CURRENT_TIMESTAMP";
    }

    /**
     * 按主键查询单行。
     *
     * @param t      表元数据
     * @param pkCols 主键列
     * @return SQL
     */
    default String buildSelectByPkSql(TableMeta t, List<String> pkCols) {
        return "SELECT * FROM " + quote(t.getTableName()) + " WHERE "
                + pkCols.stream().map(c -> quote(c) + " = ?").collect(Collectors.joining(" AND "));
    }

    /**
     * 计数语句。
     *
     * @param t           表元数据
     * @param whereClause where 片段（不含 WHERE 关键字），可空
     * @return SQL
     */
    default String buildCountSql(TableMeta t, String whereClause) {
        String sql = "SELECT COUNT(*) FROM " + quote(t.getTableName());
        return (whereClause == null || whereClause.isBlank()) ? sql : sql + " WHERE " + whereClause;
    }

    /**
     * @return 是否支持命名参数（本期全部 {@code false}）
     */
    default boolean supportsNamedParameters() {
        return false;
    }
}
