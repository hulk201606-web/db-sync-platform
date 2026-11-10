package com.dbsync.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.model.ColumnMapping;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.IndexMeta;
import com.dbsync.common.model.PrimaryKeyMeta;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.MappingOptions;
import com.dbsync.dialect.Dialect;
import com.dbsync.dialect.mysql.MySqlDialect;
import com.dbsync.dialect.postgresql.PostgresDialect;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P2 回归：列类型覆盖由「DDL 文本替换」改为「结构化生成」。
 *
 * <p>直接调用 {@link MappingService#buildDdl} —— 这是 {@code ddl()} / {@code ddlDiff()}
 * 共用的唯一 DDL 生成入口，也是旧 {@code applyOverrides} 文本替换的替代者。
 * 它只依赖方言与内存模型，无需任何 DAO / 连接 mock 即可覆盖三类历史缺陷：
 * <ol>
 *   <li>① 源表类型变化导致旧 needle 失配、覆盖被静默丢弃；</li>
 *   <li>② {@code DEFAULT}/{@code COMMENT} 字面量含相似文本被误替换；</li>
 *   <li>③ 列覆盖成 CLOB/TEXT 后仍产出对 LOB 建索引的非法 DDL。</li>
 * </ol>
 */
class MappingOverrideDdlTest {

    private final Dialect mysql = new MySqlDialect();
    private final Dialect pg = new PostgresDialect();

    /**
     * 人工覆盖映射：{@code targetType} 是生成映射时的「类型快照」，
     * {@code mappedType} 是真正的覆盖类型。旧实现的 needle 正是由快照拼出。
     */
    private static ColumnMapping override(String column, String snapshotType, String overrideType) {
        return new ColumnMapping(column, column, snapshotType, snapshotType,
                overrideType, "tester", false, false, 1);
    }

    /** NAME 列上带一个非唯一索引，用于验证覆盖后索引 DDL 的处理。 */
    private static TableMeta indexedTable() {
        return new TableMeta(null, "T_DOC",
                List.of(
                        ColumnMeta.builder("ID", 1, "BIGINT").nullable(false).build(),
                        ColumnMeta.builder("NAME", 2, "VARCHAR2").size(64).nullable(true).build()),
                new PrimaryKeyMeta("pk", List.of("ID")),
                List.of(new IndexMeta("IDX_NAME", List.of("NAME"), false)),
                0L, null);
    }

    @Test
    @DisplayName("① 源列类型变化致旧 needle 失配：结构化覆盖按列名生效，不再静默丢弃")
    void should_applyOverrideByColumnName_When_sourceTypeChanged() {
        // 生成映射时 NAME 是 VARCHAR2(64)（快照 VARCHAR(64)），此后源列被 ALTER 成 BIGINT，
        // loadTable 实时返回 BIGINT。旧 applyOverrides 的 needle=`NAME` VARCHAR(64)
        // 在实时 DDL 里匹配不到 → 覆盖被无声丢弃，NAME 会退回 BIGINT。
        TableMeta table = new TableMeta(null, "T_DOC",
                List.of(
                        ColumnMeta.builder("ID", 1, "BIGINT").nullable(false).build(),
                        ColumnMeta.builder("NAME", 2, "BIGINT").size(19).nullable(true).build()),
                new PrimaryKeyMeta("pk", List.of("ID")), List.of(), 0L, null);

        MappingService.DdlParts parts = MappingService.buildDdl(mysql, table,
                MappingOptions.defaults(), List.of(override("NAME", "VARCHAR(64)", "LONGTEXT")));

        assertThat(parts.createTable).contains("`NAME` LONGTEXT");   // 覆盖确实生效
        assertThat(parts.createTable).doesNotContain("`NAME` BIGINT"); // 未退回实时源类型
        assertThat(parts.createTable).doesNotContain("`NAME` VARCHAR(64)"); // 与旧快照解耦
    }

    @Test
    @DisplayName("② DEFAULT/COMMENT 字面量含相似文本不误替换（结构化生成不碰字面量）")
    void should_notCorruptLiterals_When_commentContainsNeedleLikeText() {
        // ID 的注释里含 `NAME` VARCHAR(64)——正是旧实现的 needle，且位置在 NAME 列之前；
        // TAG 的默认值字面量也含相似文本。结构化生成不做全局 indexOf，二者都应原样保留。
        TableMeta table = new TableMeta(null, "T_LOG",
                List.of(
                        ColumnMeta.builder("ID", 1, "BIGINT").nullable(false)
                                .comment("ref `NAME` VARCHAR(64) tail").build(),
                        ColumnMeta.builder("TAG", 2, "VARCHAR").size(32).nullable(true)
                                .defaultValue("'NAME VARCHAR(64)'").build(),
                        ColumnMeta.builder("NAME", 3, "VARCHAR2").size(64).nullable(true).build()),
                new PrimaryKeyMeta("pk", List.of("ID")), List.of(), 0L, null);

        MappingService.DdlParts parts = MappingService.buildDdl(mysql, table,
                MappingOptions.defaults(), List.of(override("NAME", "VARCHAR(64)", "LONGTEXT")));

        // 覆盖只作用于真正的列定义
        assertThat(parts.createTable).contains("`NAME` LONGTEXT");
        // 字面量（值）原样保留，没有被替换破坏
        assertThat(parts.createTable).contains("COMMENT 'ref `NAME` VARCHAR(64) tail'");
        assertThat(parts.createTable).contains("DEFAULT 'NAME VARCHAR(64)'");
    }

    @Test
    @DisplayName("③ 覆盖成 TEXT/CLOB 后跳过对该列的索引，索引 DDL 合法（MySQL + PostgreSQL）")
    void should_skipLobIndex_When_columnOverriddenToLob() {
        TableMeta table = indexedTable();

        MappingService.DdlParts pgParts = MappingService.buildDdl(pg, table,
                MappingOptions.defaults(), List.of(override("NAME", "VARCHAR(64)", "TEXT")));
        assertThat(pgParts.createTable).contains("\"NAME\" TEXT");
        assertThat(pgParts.indexDdl).isEmpty();

        MappingService.DdlParts mysqlParts = MappingService.buildDdl(mysql, table,
                MappingOptions.defaults(), List.of(override("NAME", "VARCHAR(64)", "LONGTEXT")));
        assertThat(mysqlParts.createTable).contains("`NAME` LONGTEXT");
        assertThat(mysqlParts.indexDdl).isEmpty();
    }

    @Test
    @DisplayName("覆盖成普通可索引类型时索引 DDL 仍正常生成（不误跳）")
    void should_keepIndex_When_overrideIsIndexable() {
        MappingService.DdlParts parts = MappingService.buildDdl(mysql, indexedTable(),
                MappingOptions.defaults(), List.of(override("NAME", "VARCHAR(64)", "VARCHAR(512)")));

        assertThat(parts.createTable).contains("`NAME` VARCHAR(512)");
        assertThat(parts.indexDdl).hasSize(1);
        assertThat(parts.indexDdl.get(0)).contains("CREATE INDEX").contains("`NAME`");
    }
}
