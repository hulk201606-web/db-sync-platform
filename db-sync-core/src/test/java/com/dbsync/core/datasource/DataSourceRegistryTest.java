package com.dbsync.core.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dbsync.common.enums.DbType;
import com.dbsync.common.enums.EnvType;
import com.dbsync.common.enums.IdentifierCase;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.model.DataSourceDef;
import com.dbsync.common.model.JdbcEndpoint;
import com.dbsync.core.CoreTestSupport;
import com.zaxxer.hikari.HikariConfig;
import java.sql.Connection;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T17 验收：池复用、驱逐、autoCommit=false、池配比上限。
 *
 * <p>用 {@link H2Registry} 把"建池"替换成 H2 替身（生产仍走 Hikari + 真实 MySQL/Oracle），
 * 这样 DAO/句柄逻辑可以被真实验证，而不是全靠 mock。
 */
class DataSourceRegistryTest extends CoreTestSupport {

    /** 测试用注册表：建池返回同一个 H2 DataSource，跳过驱动检查。 */
    private static final class H2Registry extends DataSourceRegistry {

        private final DataSource h2;

        H2Registry(DataSource h2, com.dbsync.metadata.dao.DataSourceDao dao) {
            super(dao, null);
            this.h2 = h2;
        }

        @Override
        protected DataSource newPool(DataSourceDef def, HikariConfig config) {
            return h2;
        }

        @Override
        protected void ensureDriverAvailable(DataSourceDef def) {
            // 单测环境没有 MySQL/Oracle 驱动 jar
        }
    }

    /**
     * 造一个数据源定义。
     *
     * @param id      数据源 ID
     * @param name    名称
     * @param poolMax 池上限
     * @return 定义
     */
    private DataSourceDef def(long id, String name, int poolMax) {
        return DataSourceDef.builder(id, name)
                .dbType(DbType.MYSQL).env(EnvType.PROD)
                .endpoint(new JdbcEndpoint("127.0.0.1", 3306, "orders", null, Map.of()))
                .username("sync").passwordCipher("plain-pwd").keyVersion((short) 1)
                .identifierCase(IdentifierCase.PRESERVE).poolMaxSize(poolMax).poolMinIdle(1)
                .fetchSize(1000).healthStatus("UNKNOWN").build();
    }

    @Test
    @DisplayName("should_reusePool_When_handleCalledTwice：同一 dsId 返回同一句柄")
    void should_reusePool_When_handleCalledTwice() {
        dataSourceDao.insert(def(1L, "orders-prod", 8), "alice");
        DataSourceRegistry registry = new H2Registry(dataSource, dataSourceDao);
        DataSourceHandle first = registry.handle(1L);
        DataSourceHandle second = registry.handle(1L);
        assertThat(second).isSameAs(first);
        assertThat(registry.cachedIds()).containsExactly(Long.valueOf(1L));
    }

    @Test
    @DisplayName("should_closePool_When_evicted：驱逐后句柄失效，再次 handle 重建新句柄")
    void should_closePool_When_evicted() {
        dataSourceDao.insert(def(2L, "orders-uat", 4), "alice");
        DataSourceRegistry registry = new H2Registry(dataSource, dataSourceDao);
        DataSourceHandle first = registry.handle(2L);
        registry.evict(2L);
        assertThat(first.isClosed()).isTrue();
        assertThat(registry.cachedIds()).isEmpty();
        DataSourceHandle rebuilt = registry.handle(2L);
        assertThat(rebuilt).isNotSameAs(first);
    }

    @Test
    @DisplayName("should_connectWithAutoCommitFalse：业务池默认非自动提交（fetchSize 生效前提）")
    void should_connectWithAutoCommitFalse() throws Exception {
        dataSourceDao.insert(def(3L, "orders-analyze", 4), "alice");
        // 替身用 Hikari 建真池，验证"建池时 autoCommit=false"这条硬约束
        DataSourceRegistry registry = new DataSourceRegistry(dataSourceDao, null) {
            @Override
            protected DataSource newPool(DataSourceDef d, HikariConfig config) {
                assertThat(config.isAutoCommit()).isFalse();
                HikariConfig h2 = new HikariConfig();
                h2.setJdbcUrl(dataSource.getURL());
                h2.setUsername("sa");
                h2.setAutoCommit(config.isAutoCommit());
                return new com.zaxxer.hikari.HikariDataSource(h2);
            }

            @Override
            protected void ensureDriverAvailable(DataSourceDef d) {
                // 跳过驱动检查
            }
        };
        DataSourceHandle handle = registry.handle(3L);
        try (Connection connection = handle.connection()) {
            assertThat(connection.getAutoCommit()).isFalse();
        }
    }

    @Test
    @DisplayName("should_throwE2010_When_poolSizeExceeds60Percent：池上限 > 0.6×max_connections")
    void should_throwE2010_When_poolSizeExceeds60Percent() {
        // max_connections = 100 → 允许 60，配置 80 必须被拒
        assertThatThrownBy(() -> DataSourceRegistry.verifyPoolRatio(def(4L, "x", 80), 80, 100))
                .isInstanceOf(DbSyncException.class)
                .satisfies(e -> assertThat(((DbSyncException) e).errorCode().code())
                        .isEqualTo("E2010"));
        // 60 恰好允许
        DataSourceRegistry.verifyPoolRatio(def(5L, "y", 60), 60, 100);
        // 取不到 max_connections 时跳过校验（不能因探测失败而阻断接入）
        DataSourceRegistry.verifyPoolRatio(def(6L, "z", 1000), 1000, 0);
    }

    @Test
    @DisplayName("should_throwE2004_When_dsNotFound：dsId 不存在抛 E2004")
    void should_throwE2004_When_dsNotFound() {
        DataSourceRegistry registry = new H2Registry(dataSource, dataSourceDao);
        assertThatThrownBy(() -> registry.handle(999L))
                .isInstanceOf(DbSyncException.class)
                .satisfies(e -> assertThat(((DbSyncException) e).errorCode().code())
                        .isEqualTo("E2004"));
    }

    @Test
    @DisplayName("should_closeAll：close() 关闭全部池")
    void should_closeAll() {
        dataSourceDao.insert(def(7L, "a", 2), "alice");
        dataSourceDao.insert(def(8L, "b", 2), "alice");
        DataSourceRegistry registry = new H2Registry(dataSource, dataSourceDao);
        DataSourceHandle a = registry.handle(7L);
        DataSourceHandle b = registry.handle(8L);
        registry.close();
        assertThat(a.isClosed()).isTrue();
        assertThat(b.isClosed()).isTrue();
        assertThat(registry.cachedIds()).isEmpty();
    }
}
