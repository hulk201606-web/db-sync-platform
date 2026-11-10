package com.dbsync.dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dbsync.common.enums.DbType;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.DriverMissingException;
import com.dbsync.common.model.JdbcEndpoint;
import com.dbsync.common.options.MappingOptions;
import com.dbsync.common.options.TypeMappingOptions;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 方言注册表 + 驱动加载 + 通用模板单测。
 */
class DialectRegistryTest {

    @Test
    @DisplayName("5 种方言均注册且返回单例")
    void should_returnSingleton_When_getByType() {
        assertThat(DialectFixtures.registeredCount()).isEqualTo(5);
        assertThat(DialectRegistry.get(DbType.MYSQL)).isSameAs(DialectRegistry.get(DbType.MYSQL));
        assertThat(DialectRegistry.all()).hasSize(5);
    }

    @Test
    @DisplayName("按 JDBC URL 取方言")
    void should_resolveByUrl_When_getByJdbcUrl() {
        assertThat(DialectRegistry.getByJdbcUrl("jdbc:mysql://h:3306/db")).isInstanceOf(
                com.dbsync.dialect.mysql.MySqlDialect.class);
        assertThat(DialectRegistry.getByJdbcUrl("jdbc:dm://h:5236")).isInstanceOf(
                com.dbsync.dialect.dm.DmDialect.class);
        assertThat(DialectRegistry.getByJdbcUrl("jdbc:oracle:thin:@//h:1521/orcl")).isInstanceOf(
                com.dbsync.dialect.oracle.OracleDialect.class);
    }

    @Test
    @DisplayName("未知 URL 抛 E7010")
    void should_throwE7010_When_unknownUrl() {
        DbSyncException ex = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> DialectRegistry.getByJdbcUrl("jdbc:unknown://h"),
                DbSyncException.class);
        assertThat(ex.errorCode().code()).isEqualTo("E7010");
    }

    @Test
    @DisplayName("驱动缺失消息含 lib/ 与三种加载方式")
    void should_buildUsefulMessage_When_driverMissing() {
        assertThat(JdbcDriverLoader.isAvailable("org.h2.Driver")).isTrue();
        assertThat(JdbcDriverLoader.isAvailable("no.such.Driver")).isFalse();
        DriverMissingException ex = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> JdbcDriverLoader.ensureLoaded("com.ibm.db2.jcc.DB2Driver"),
                DriverMissingException.class);
        assertThat(ex.getMessage()).contains("com.ibm.db2.jcc.DB2Driver");
        DialectFixtures.assertDriverMessage(ex);
        assertThatThrownBy(() -> JdbcDriverLoader.ensureLoaded(null))
                .isInstanceOf(DriverMissingException.class);
        assertThat(JdbcDriverLoader.availableDrivers()).isNotEmpty();
    }

    @Test
    @DisplayName("AbstractDialect.quote 正确转义内部引号")
    void should_escapeQuote_When_identifierContainsQuote() {
        Dialect mysql = DialectRegistry.get(DbType.MYSQL);
        Dialect pg = DialectRegistry.get(DbType.POSTGRESQL);
        assertThat(mysql.quote("a`b")).isEqualTo("`a``b`");
        assertThat(pg.quote("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(pg.quote(null)).isNull();
        assertThat(mysql.normalize("Abc", com.dbsync.common.enums.IdentifierCase.UPPER))
                .isEqualTo("ABC");
        assertThat(mysql.normalize("Abc", com.dbsync.common.enums.IdentifierCase.PRESERVE))
                .isEqualTo("Abc");
    }

    @Test
    @DisplayName("JDBC URL 拼装按方言前缀")
    void should_buildUrl_When_endpointGiven() {
        JdbcEndpoint endpoint = new JdbcEndpoint("10.0.0.1", 3306, "dbsync",
                "public", Map.of("useSSL", "false"));
        assertThat(DialectRegistry.get(DbType.MYSQL).buildJdbcUrl(endpoint))
                .isEqualTo("jdbc:mysql://10.0.0.1:3306/dbsync?useSSL=false");
        assertThat(DialectRegistry.get(DbType.ORACLE)
                .buildJdbcUrl(new JdbcEndpoint("h", 1521, "orcl", null, Map.of())))
                .isEqualTo("jdbc:oracle:thin:@//h:1521/orcl");
    }

    @Test
    @DisplayName("R9：无精度 NUMBER 默认 (38,10)")
    void should_mapUnboundedNumber_When_R9() {
        com.dbsync.common.model.ColumnMeta col = com.dbsync.common.model.ColumnMeta
                .builder("AMT", 1, "NUMBER").build();
        TypeMappingOptions opts = TypeMappingOptions.defaults();
        assertThat(DialectRegistry.get(DbType.MYSQL).mapType(col, opts)).isEqualTo("DECIMAL(38,10)");
        assertThat(DialectRegistry.get(DbType.POSTGRESQL).mapType(col, opts))
                .isEqualTo("NUMERIC(38,10)");
        assertThat(DialectRegistry.get(DbType.DM).mapType(col, opts)).isEqualTo("DECIMAL(38,10)");
        assertThat(DialectRegistry.get(DbType.DB2).mapType(col, opts)).isEqualTo("DECIMAL(38,10)");
        assertThat(DialectRegistry.get(DbType.ORACLE).mapType(col, opts)).isEqualTo("NUMBER(38,10)");
    }

    @Test
    @DisplayName("DDL 生成不抛异常且每种方言都有 PRIMARY KEY")
    void should_generateDdl_When_allDialects() {
        for (DbType t : DbType.values()) {
            Dialect d = DialectRegistry.get(t);
            String ddl = d.createTableDdl(DialectFixtures.singlePk(), MappingOptions.defaults());
            assertThat(ddl).contains("CREATE TABLE");
            assertThat(ddl).contains("PRIMARY KEY");
            assertThat(ddl).contains("T_ORDER");
            assertThat(d.createIndexDdl(DialectFixtures.singlePk(), MappingOptions.defaults()))
                    .as("%s 索引", t).isNotNull();
            assertThat(d.commentDdl(DialectFixtures.singlePk(), MappingOptions.defaults()))
                    .as("%s 注释", t).isNotNull();
            assertThat(d.capabilities()).isNotNull();
            assertThat(d.metadata()).isNotNull();
            assertThat(d.trigger()).isNotNull();
        }
    }
}
