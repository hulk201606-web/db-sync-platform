package com.dbsync.core.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.enums.DbType;
import com.dbsync.common.enums.EnvType;
import com.dbsync.common.enums.IdentifierCase;
import com.dbsync.common.model.DataSourceDef;
import com.dbsync.common.model.JdbcEndpoint;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T17 验收：连接失败必须归到四类之一（AUTH / NETWORK / DRIVER / PRIVILEGE），
 * 运维据此判断"该改配置、找网络组、放驱动，还是找 DBA 授权"。
 */
class ConnectionTesterTest {

    private final ConnectionTester tester = new ConnectionTester(null);

    /**
     * 造一个数据源定义。
     *
     * @param dbType 数据库类型
     * @return 定义
     */
    private DataSourceDef def(DbType dbType) {
        return DataSourceDef.builder(1L, "probe-" + dbType)
                .dbType(dbType).env(EnvType.PROD)
                .endpoint(new JdbcEndpoint("127.0.0.1", 3306, "orders", null, Map.of()))
                .username("sync").passwordCipher("pwd").keyVersion((short) 1)
                .identifierCase(IdentifierCase.PRESERVE).poolMaxSize(2).poolMinIdle(1)
                .fetchSize(100).healthStatus("UNKNOWN").build();
    }

    @Test
    @DisplayName("should_classifyAuth：口令错误 → AUTH（SQLState 28000 / MySQL 1045）")
    void should_classifyAuth() {
        ConnectionTestResult byState = tester.classify(
                new SQLException("Access denied", "28000", 1045), DbType.MYSQL, 12L);
        assertThat(byState.isSuccess()).isFalse();
        assertThat(byState.getCategory()).isEqualTo(ConnectionTestResult.Category.AUTH);
        assertThat(byState.getHint()).contains("用户名");

        ConnectionTestResult ora = tester.classify(
                new SQLException("ORA-01017: invalid username/password", "72000", 1017),
                DbType.ORACLE, 12L);
        assertThat(ora.getCategory()).isEqualTo(ConnectionTestResult.Category.AUTH);
    }

    @Test
    @DisplayName("should_classifyNetwork：连不上 → NETWORK（SQLState 08xxx / Connection refused）")
    void should_classifyNetwork() {
        assertThat(tester.classify(new SQLException("io error", "08001", 0), DbType.MYSQL, 5L)
                .getCategory()).isEqualTo(ConnectionTestResult.Category.NETWORK);
        assertThat(tester.classify(
                new SQLException("Communications link failure", null, 0), DbType.MYSQL, 5L)
                .getCategory()).isEqualTo(ConnectionTestResult.Category.NETWORK);
        assertThat(tester.classify(
                new SQLException("Connection refused (Connection refused)", null, 0),
                DbType.POSTGRESQL, 5L).getCategory())
                .isEqualTo(ConnectionTestResult.Category.NETWORK);
    }

    @Test
    @DisplayName("should_classifyPrivilege：能认证但无库权限 → PRIVILEGE")
    void should_classifyPrivilege() {
        assertThat(tester.classify(
                new SQLException("Access denied for user 'sync' to database 'orders'", null, 1044),
                DbType.MYSQL, 8L).getCategory())
                .isEqualTo(ConnectionTestResult.Category.PRIVILEGE);
        assertThat(tester.classify(
                new SQLException("ORA-01031: insufficient privileges", null, 1031),
                DbType.ORACLE, 8L).getCategory())
                .isEqualTo(ConnectionTestResult.Category.PRIVILEGE);
    }

    @Test
    @DisplayName("should_classifyDriver：No suitable driver → DRIVER（提示 lib/ 放置方式）")
    void should_classifyDriver() {
        ConnectionTestResult result = tester.classify(
                new SQLException("No suitable driver found for jdbc:mysql://...", null, 0),
                DbType.MYSQL, 1L);
        assertThat(result.getCategory()).isEqualTo(ConnectionTestResult.Category.DRIVER);
        assertThat(result.getHint()).contains("lib/");
    }

    @Test
    @DisplayName("should_reportDriverMissing_When_oracleDriverAbsent：无 jar 时直接返回 DRIVER，不去连库")
    void should_reportDriverMissing_When_oracleDriverAbsent() {
        ConnectionTestResult result = tester.test(def(DbType.ORACLE));
        // 单测 classpath 里没有 ojdbc（授权原因），必然归为 DRIVER
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getCategory()).isEqualTo(ConnectionTestResult.Category.DRIVER);
        assertThat(result.getErrorCode()).isEqualTo("E7001");
        assertThat(result.getHint()).contains("lib/");
    }

    @Test
    @DisplayName("should_notMisclassifyUnknown：未归类异常归 UNKNOWN，不猜成网络问题")
    void should_notMisclassifyUnknown() {
        ConnectionTestResult result = tester.classify(
                new SQLException("weird vendor error 0x1234", "HY000", 9999), DbType.DB2, 3L);
        assertThat(result.getCategory()).isEqualTo(ConnectionTestResult.Category.UNKNOWN);
        assertThat(result.getMessage()).contains("0x1234");
    }
}
