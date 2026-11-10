package com.dbsync.dialect.dm;

import com.dbsync.common.enums.DbType;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.options.TypeMappingOptions;
import com.dbsync.dialect.Capabilities;
import com.dbsync.dialect.MetadataReader;
import com.dbsync.dialect.TriggerDdlGenerator;
import com.dbsync.dialect.oracle.OracleDialect;
import java.util.Locale;

/**
 * 达梦 DM8 方言。
 *
 * <p>与 Oracle 的差异（架构 §5.2 / §5.3 的"同 Oracle"列 + 实测差异）：
 * <ul>
 *   <li>{@code NUMBER} → {@code DECIMAL}（DM 的 NUMBER 兼容但 DECIMAL 更贴近其原生类型）；</li>
 *   <li>分页统一用 {@code LIMIT ? OFFSET ?}（DM 原生支持，比 ROWNUM 简单且不依赖嵌套）；</li>
 *   <li>{@code supportsSkipLocked = false}（架构 §4.2.3：DM 默认不支持 SKIP LOCKED，
 *       抢占降级为 NOWAIT_PROBE / OPTIMISTIC_CAS）；</li>
 *   <li>标识符上限 128，且引号内大小写敏感（受 {@code CASE_SENSITIVE} 参数影响）→
 *       {@code caseSensitiveIdentifiers = true}；</li>
 *   <li>{@code MERGE} 的 {@code USING} 子查询列必须与 {@code INSERT} 列列表<b>严格同序</b>，
 *       实现已按同一 {@code cols} 顺序生成，改动时务必保持。</li>
 * </ul>
 */
public final class DmDialect extends OracleDialect {

    private final MetadataReader metadataReader = new DmMetadataReader();
    private final TriggerDdlGenerator triggerGenerator = new DmTriggerDdlGenerator();
    private final Capabilities capabilities;

    /**
     * 构造（DM8：128 字符标识符）。
     */
    public DmDialect() {
        this(128);
    }

    /**
     * 构造。
     *
     * @param maxIdentifierLength 标识符上限
     */
    public DmDialect(int maxIdentifierLength) {
        super(Pagination.ROWNUM, maxIdentifierLength);
        this.capabilities = new Capabilities(
                false,  // supportsSkipLocked ★（DM 默认 false）
                true,   // supportsMerge
                false,  // supportsOnConflict
                false,  // supportsOnDuplicateKey
                false,  // supportsIdentityInsert
                true,   // supportsIdentityRestart
                true,   // needsFetchSizeCursor（同 Oracle）
                true,   // supportsCommentOn
                false,  // supportsMultiRowValues
                false,  // supportsBatchGeneratedKeys
                true,   // supportsStreamingBlob
                true,   // caseSensitiveIdentifiers
                maxIdentifierLength <= 0 ? 128 : maxIdentifierLength,
                500,    // recommendedBatchSize
                "SYS_CONTEXT('USERENV','CURRENT_SCHEMA')");
    }

    @Override
    public DbType type() {
        return DbType.DM;
    }

    @Override
    public String driverClassName() {
        return DbType.DM.driverClassName();
    }

    @Override
    public String mapType(ColumnMeta src, TypeMappingOptions opts) {
        TypeMappingOptions options = opts == null ? TypeMappingOptions.defaults() : opts;
        String t = src.getTypeName() == null ? "VARCHAR" : src.getTypeName().toUpperCase(Locale.ROOT);
        int size = src.getSize();
        int scale = src.getDecimalDigits();
        if ("NUMBER".equals(t) || "DECIMAL".equals(t) || "NUMERIC".equals(t)) {
            if (size <= 0) {
                return "DECIMAL(" + options.getDefaultNumberPrecision() + ","
                        + options.getDefaultNumberScale() + ")";
            }
            if (scale <= 0) {
                return "DECIMAL(" + size + ")";
            }
            return "DECIMAL(" + size + "," + scale + ")";
        }
        return super.mapType(src, opts);
    }

    @Override
    public String buildPageSql(String baseSql, long offset, int limit) {
        // DM 原生支持 LIMIT ? OFFSET ?，不需要 ROWNUM 嵌套
        return (baseSql == null ? "" : baseSql) + " LIMIT " + limit + " OFFSET " + offset;
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
}
