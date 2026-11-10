package com.dbsync.dialect;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.enums.IdentifierCase;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.IndexMeta;
import com.dbsync.common.model.PrimaryKeyMeta;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.MappingOptions;
import com.dbsync.common.options.TypeMappingOptions;
import com.dbsync.dialect.dm.DmDialect;
import com.dbsync.dialect.mysql.MySqlDialect;
import com.dbsync.dialect.oracle.OracleDialect;
import com.dbsync.dialect.postgresql.PostgresDialect;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MAP-02 验收：{@code identifierCase} 必须真正改变<b>目标端 DDL</b>里的标识符，
 * 而不是只做「枚举 + DB 列 + 回显」却让 {@code normalize()} 无人调用。
 *
 * <p>用混合大小写的表名/列名/索引名，才能把 UPPER / LOWER / PRESERVE 三种策略区分开
 * （{@link DialectFixtures} 的夹具全是大写，UPPER 与 PRESERVE 会看不出差别）。
 * 断言覆盖完整 CREATE TABLE 与 CREATE INDEX，不只断言 normalize 方法本身。
 */
class IdentifierCaseDdlTest {

    private static MappingOptions options(IdentifierCase strategy) {
        return new MappingOptions(TypeMappingOptions.defaults(), strategy,
                true, true, true, false, null);
    }

    private static TableMeta mixedCaseTable() {
        List<ColumnMeta> columns = List.of(
                ColumnMeta.builder("OrderId", 1, "BIGINT").nullable(false).build(),
                ColumnMeta.builder("UserName", 2, "VARCHAR").size(64).nullable(true).build());
        return new TableMeta("App", "MyTable", columns,
                new PrimaryKeyMeta("PkMyTable", List.of("OrderId")),
                List.of(new IndexMeta("IdxUserName", List.of("UserName"), false)),
                100L, null);
    }

    private static void assertStrategyWorks(Dialect dialect) {
        TableMeta table = mixedCaseTable();
        String upper = dialect.createTableDdl(table, options(IdentifierCase.UPPER));
        String lower = dialect.createTableDdl(table, options(IdentifierCase.LOWER));
        String preserve = dialect.createTableDdl(table, MappingOptions.defaults());

        // 表名 + 列名都要归一化；断言不含引号，兼容反引号/双引号两种风格
        assertThat(upper).contains("MYTABLE").contains("ORDERID").contains("USERNAME")
                .doesNotContain("MyTable").doesNotContain("OrderId");
        assertThat(lower).contains("mytable").contains("orderid").contains("username")
                .doesNotContain("MyTable").doesNotContain("OrderId");
        assertThat(preserve).contains("MyTable").contains("OrderId").contains("UserName");

        // 索引名与索引列同样走 quoteTarget，不能只改 CREATE TABLE
        List<String> upperIndexes = dialect.createIndexDdl(table, options(IdentifierCase.UPPER));
        List<String> lowerIndexes = dialect.createIndexDdl(table, options(IdentifierCase.LOWER));
        assertThat(upperIndexes).as("UPPER 应生成非唯一索引 DDL").hasSize(1);
        assertThat(upperIndexes.get(0)).contains("IDXUSERNAME").contains("MYTABLE");
        assertThat(lowerIndexes).hasSize(1);
        assertThat(lowerIndexes.get(0)).contains("idxusername").contains("mytable");
    }

    @Test
    @DisplayName("MySQL：UPPER/LOWER 真实改变建表与索引 DDL，PRESERVE 保持原样")
    void should_normalizeIdentifiers_When_mysql() {
        assertStrategyWorks(new MySqlDialect());
    }

    @Test
    @DisplayName("PostgreSQL：UPPER/LOWER 真实改变建表与索引 DDL")
    void should_normalizeIdentifiers_When_postgresql() {
        assertStrategyWorks(new PostgresDialect());
    }

    @Test
    @DisplayName("Oracle：UPPER/LOWER 真实改变建表与索引 DDL")
    void should_normalizeIdentifiers_When_oracle() {
        assertStrategyWorks(new OracleDialect());
    }

    @Test
    @DisplayName("DM8 继承 Oracle：同样生效，不能成为漏网方言")
    void should_normalizeIdentifiers_When_dm8() {
        assertStrategyWorks(new DmDialect());
    }
}
