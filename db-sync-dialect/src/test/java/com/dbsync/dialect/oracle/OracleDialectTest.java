package com.dbsync.dialect.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.MappingOptions;
import com.dbsync.common.options.TriggerOptions;
import com.dbsync.common.options.TypeMappingOptions;
import com.dbsync.dialect.DialectFixtures;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Oracle + DM 方言纯字符串断言（DM 与 Oracle 共享大量语法，放在一起对照更直观）。
 */
class OracleDialectTest {

    private final OracleDialect oracle = new OracleDialect();
    private final OracleDialect oracle12c = new OracleDialect(
            OracleDialect.Pagination.FETCH_FIRST, 128);

    @Test
    @DisplayName("upsert 用 MERGE INTO + USING(SELECT ... FROM DUAL)")
    void should_useMerge_When_buildUpsertSql() {
        String sql = oracle.buildUpsertSql(DialectFixtures.compositePk(),
                List.of("TENANT_ID", "ORDER_ID"), List.of("TENANT_ID", "ORDER_ID", "QTY"));
        assertThat(sql).contains("MERGE INTO \"T_ORDER_ITEM\" tgt");
        assertThat(sql).contains("USING (SELECT ? AS \"TENANT_ID\"");
        assertThat(sql).contains("FROM DUAL");
        assertThat(sql).contains("WHEN MATCHED THEN UPDATE SET tgt.\"QTY\" = src.\"QTY\"");
        assertThat(sql).contains("WHEN NOT MATCHED THEN INSERT");
        assertThat(sql).doesNotContain("UPDATE SET tgt.\"ORDER_ID\"");
    }

    @Test
    @DisplayName("分页两种模式：ROWNUM 与 FETCH FIRST")
    void should_supportBothPagination_When_oracle() {
        String rownum = oracle.buildPageSql("SELECT * FROM t", 10, 20);
        assertThat(rownum).contains("ROWNUM").contains("ROWNUM <= 30").contains("rn > 10");
        String fetchFirst = oracle12c.buildPageSql("SELECT * FROM t", 10, 20);
        assertThat(fetchFirst).contains("OFFSET 10 ROWS FETCH NEXT 20 ROWS ONLY");
        assertThat(oracle.pagination()).isEqualTo(OracleDialect.Pagination.ROWNUM);
    }

    @Test
    @DisplayName("能力：needsFetchSizeCursor=true，标识符 30，批 500")
    void should_requireFetchSizeCursor_When_oracle() {
        assertThat(oracle.capabilities().needsFetchSizeCursor()).isTrue();
        assertThat(oracle.capabilities().supportsMerge()).isTrue();
        assertThat(oracle.capabilities().supportsMultiRowValues()).isFalse();
        assertThat(oracle.capabilities().maxIdentifierLength()).isEqualTo(30);
        assertThat(oracle.capabilities().recommendedBatchSize()).isEqualTo(500);
        assertThat(oracle12c.capabilities().maxIdentifierLength()).isEqualTo(128);
        assertThat(oracle.currentTimestampExpr()).isEqualTo("SYSTIMESTAMP");
    }

    @Test
    @DisplayName("建表 DDL 无 IF NOT EXISTS，类型映射保留 NUMBER 精度")
    void should_mapTypes_When_oracle() {
        String ddl = oracle.createTableDdl(DialectFixtures.singlePk(), MappingOptions.defaults());
        assertThat(ddl).contains("CREATE TABLE \"APP\".\"T_ORDER\"");
        assertThat(ddl).doesNotContain("IF NOT EXISTS");
        assertThat(ddl).contains("\"AMT\" NUMBER(38,10)");
        assertThat(ddl).contains("PRIMARY KEY (\"ID\")");
        assertThat(ddl).contains("CONSTRAINT \"UK_ORDER_NAME\" UNIQUE (\"NAME\")");
        TypeMappingOptions opts = TypeMappingOptions.defaults();
        assertThat(oracle.mapType(ColumnMeta.builder("A", 1, "NUMBER").size(38)
                .decimalDigits(10).build(), opts)).isEqualTo("NUMBER(38,10)");
        assertThat(oracle.mapType(ColumnMeta.builder("A", 1, "CLOB").build(), opts))
                .isEqualTo("CLOB");
        assertThat(oracle.mapType(ColumnMeta.builder("A", 1, "VARCHAR2").size(64).build(), opts))
                .isEqualTo("VARCHAR2(64)");
    }

    @Test
    @DisplayName("触发器 3 条且每条含 :NEW. 或 :OLD. 冒号前缀")
    void should_prefixColon_When_oracleTrigger() {
        TableMeta t = DialectFixtures.singlePk();
        List<String> triggers = oracle.trigger().createTriggerDdl(t, TriggerOptions.defaults());
        assertThat(triggers).hasSize(3);
        for (String s : triggers) {
            assertThat(s).contains("FOR EACH ROW").contains("BEGIN");
        }
        assertThat(triggers.get(0)).contains(":NEW.");
        assertThat(triggers.get(2)).contains(":OLD.");
        assertThat(triggers.get(1)).contains(":NEW.").contains(":OLD.");
        assertThat(oracle.trigger().pollSql("\"T_ORDER_SYNC_LOG\"", 0L, 100))
                .contains("WHERE \"ID\" > 0");
    }

    @Test
    @DisplayName("DM：分页 LIMIT/OFFSET、不支持 SKIP LOCKED、双引号引用")
    void should_behaveLikeDm_When_dmDialect() {
        com.dbsync.dialect.dm.DmDialect dm = new com.dbsync.dialect.dm.DmDialect();
        assertThat(dm.buildPageSql("SELECT * FROM t", 5, 50))
                .isEqualTo("SELECT * FROM t LIMIT 50 OFFSET 5");
        assertThat(dm.capabilities().supportsSkipLocked()).isFalse();
        assertThat(dm.capabilities().maxIdentifierLength()).isEqualTo(128);
        assertThat(dm.capabilities().caseSensitiveIdentifiers()).isTrue();
        assertThat(dm.quote("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(dm.mapType(ColumnMeta.builder("A", 1, "NUMBER").size(38).decimalDigits(10).build(),
                TypeMappingOptions.defaults())).isEqualTo("DECIMAL(38,10)");
        assertThat(dm.buildUpsertSql(DialectFixtures.compositePk(),
                List.of("TENANT_ID"), List.of("TENANT_ID", "QTY"))).contains("MERGE INTO");
        assertThat(dm.trigger().createTriggerDdl(DialectFixtures.singlePk(),
                TriggerOptions.defaults())).hasSize(3);
        assertThat(dm.driverClassName()).isEqualTo("dm.jdbc.driver.DmDriver");
    }
}
