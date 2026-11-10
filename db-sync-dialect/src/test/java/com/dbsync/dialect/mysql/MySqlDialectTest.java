package com.dbsync.dialect.mysql;

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
 * MySQL 方言纯字符串断言（不连库，架构 §1.8）。
 */
class MySqlDialectTest {

    private final MySqlDialect dialect = new MySqlDialect();

    @Test
    @DisplayName("建表 DDL 含反引号/主键/唯一键/注释/自增")
    void should_containKeyFragments_When_createTableDdl() {
        String ddl = dialect.createTableDdl(DialectFixtures.singlePk(), MappingOptions.defaults());
        assertThat(ddl).contains("CREATE TABLE");
        assertThat(ddl).contains("`ID` BIGINT NOT NULL AUTO_INCREMENT");
        assertThat(ddl).contains("PRIMARY KEY (`ID`)");
        assertThat(ddl).contains("UNIQUE KEY `UK_ORDER_NAME` (`NAME`)");
        assertThat(ddl).contains("COMMENT '主键'");
        assertThat(ddl).contains("`AMT` DECIMAL(38,10)");
        assertThat(ddl).contains("`NAME` VARCHAR(64)");
        assertThat(ddl).contains("`CREATED_AT` DATETIME(3)");
    }

    @Test
    @DisplayName("upsert 用 ON DUPLICATE KEY UPDATE 且 SET 不含主键列")
    void should_excludePkFromUpdate_When_buildUpsertSql() {
        String sql = dialect.buildUpsertSql(DialectFixtures.singlePk(), List.of("ID"),
                List.of("ID", "AMT", "NAME"));
        assertThat(sql).contains("INSERT INTO `T_ORDER` (`ID`, `AMT`, `NAME`)");
        assertThat(sql).contains("ON DUPLICATE KEY UPDATE");
        assertThat(sql).contains("`AMT`=VALUES(`AMT`)");
        assertThat(sql).doesNotContain("`ID`=VALUES(`ID`)");
    }

    @Test
    @DisplayName("分页：无 ORDER BY 直接 LIMIT，有 ORDER BY 包一层子查询")
    void should_wrapSubQuery_When_orderByPresent() {
        assertThat(dialect.buildPageSql("SELECT * FROM t", 0, 10))
                .isEqualTo("SELECT * FROM t LIMIT 0, 10");
        assertThat(dialect.buildPageSql("SELECT * FROM t ORDER BY id", 10, 20))
                .startsWith("SELECT * FROM (").contains(") _p LIMIT 10, 20");
    }

    @Test
    @DisplayName("上界谓词开闭可选，下界固定 >")
    void should_buildRangePredicate_When_flagGiven() {
        assertThat(dialect.buildRangePredicate("`id`", false)).isEqualTo("`id` < ?");
        assertThat(dialect.buildRangePredicate("`id`", true)).isEqualTo("`id` <= ?");
        assertThat(dialect.buildLowerPredicate("`id`")).isEqualTo("`id` > ?");
    }

    @Test
    @DisplayName("quote 转义反引号；非唯一索引单独 CREATE INDEX；注释仍内联（返回空）")
    void should_emitCreateIndexForNonUnique_When_mysql() {
        assertThat(dialect.quote("a`b")).isEqualTo("`a``b`");
        // // FIXED: 原先此处断言 isEmpty()，但那正是 MAP-03 的缺口——MySQL 只内联唯一键，
        // 非唯一索引必须单独建，否则目标库索引数量永远对不上源端。
        List<String> indexDdl = dialect.createIndexDdl(DialectFixtures.singlePk(),
                MappingOptions.defaults());
        assertThat(indexDdl).hasSize(1);
        assertThat(indexDdl.get(0))
                .isEqualTo("CREATE INDEX `IDX_ORDER_AMT` ON `APP`.`T_ORDER` (`AMT`)");
        assertThat(indexDdl.get(0)).doesNotContain("UK_ORDER_NAME");
        assertThat(dialect.commentDdl(DialectFixtures.singlePk(), MappingOptions.defaults()))
                .isEmpty();
    }

    @Test
    @DisplayName("类型映射：NUMBER(19,0)→BIGINT、NUMBER(38,10)→DECIMAL、VARCHAR2→VARCHAR")
    void should_mapTypes_When_oracleSourceTypes() {
        TypeMappingOptions opts = TypeMappingOptions.defaults();
        assertThat(dialect.mapType(ColumnMeta.builder("A", 1, "NUMBER").size(19).build(), opts))
                .isEqualTo("BIGINT");
        assertThat(dialect.mapType(ColumnMeta.builder("B", 1, "NUMBER").size(38)
                .decimalDigits(10).build(), opts)).isEqualTo("DECIMAL(38,10)");
        assertThat(dialect.mapType(ColumnMeta.builder("C", 1, "VARCHAR2").size(64).build(), opts))
                .isEqualTo("VARCHAR(64)");
        assertThat(dialect.mapType(ColumnMeta.builder("D", 1, "CLOB").build(), opts))
                .isEqualTo("LONGTEXT");
        assertThat(dialect.mapType(ColumnMeta.builder("E", 1, "BLOB").build(), opts))
                .isEqualTo("LONGBLOB");
        assertThat(dialect.mapType(ColumnMeta.builder("F", 1, "DATE").build(), opts))
                .isEqualTo("DATETIME(3)");
    }

    @Test
    @DisplayName("能力声明：ON DUPLICATE KEY / 64 字符 / 批 1000")
    void should_declareCapabilities_When_mysql() {
        assertThat(dialect.capabilities().supportsOnDuplicateKey()).isTrue();
        assertThat(dialect.capabilities().supportsMerge()).isFalse();
        assertThat(dialect.capabilities().supportsOnConflict()).isFalse();
        assertThat(dialect.capabilities().needsFetchSizeCursor()).isFalse();
        assertThat(dialect.capabilities().maxIdentifierLength()).isEqualTo(64);
        assertThat(dialect.capabilities().recommendedBatchSize()).isEqualTo(1000);
        assertThat(dialect.capabilities().supportsCommentOn()).isFalse();
        assertThat(dialect.driverClassName()).isEqualTo("com.mysql.cj.jdbc.Driver");
    }

    @Test
    @DisplayName("触发器：3 条 DDL、NEW./OLD. 变量、日志表自增主键")
    void should_generateThreeTriggers_When_createTriggerDdl() {
        TableMeta t = DialectFixtures.singlePk();
        List<String> logDdl = dialect.trigger().createChangeLogTableDdl(t, TriggerOptions.defaults());
        assertThat(logDdl.get(0)).contains("`id` BIGINT NOT NULL AUTO_INCREMENT");
        assertThat(logDdl.get(0)).contains("PRIMARY KEY (`id`)");
        List<String> triggers = dialect.trigger().createTriggerDdl(t, TriggerOptions.defaults());
        assertThat(triggers).hasSize(3);
        assertThat(triggers.get(0)).contains("AFTER INSERT").contains("NEW.`ID`");
        assertThat(triggers.get(1)).contains("AFTER UPDATE").contains("OLD.`ID`");
        assertThat(triggers.get(2)).contains("AFTER DELETE").contains("OLD.`ID`");
        assertThat(dialect.trigger().pollSql("`T_ORDER_SYNC_LOG`", 100L, 500))
                .contains("WHERE `id` > 100").contains("ORDER BY `id`");
        assertThat(dialect.trigger().changeLogTableName("T_ORDER")).isEqualTo("T_ORDER_SYNC_LOG");
    }
}
