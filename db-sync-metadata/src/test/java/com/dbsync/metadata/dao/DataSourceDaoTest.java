package com.dbsync.metadata.dao;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.enums.DbType;
import com.dbsync.common.enums.EnvType;
import com.dbsync.common.enums.IdentifierCase;
import com.dbsync.common.model.DataSourceDef;
import com.dbsync.common.model.JdbcEndpoint;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 数据源 DAO CRUD 测试（含乐观锁与被引用保护）。
 */
class DataSourceDaoTest extends MetaDaoTestSupport {

    /**
     * 造一个数据源定义。
     *
     * @param id   数据源 ID
     * @param name 名称
     * @return 定义
     */
    private DataSourceDef def(long id, String name) {
        return DataSourceDef.builder(id, name)
                .dbType(DbType.MYSQL)
                .env(EnvType.PROD)
                .endpoint(new JdbcEndpoint("127.0.0.1", 3306, "orders", null, Map.of()))
                .username("sync_readonly")
                .passwordCipher("Y2lwaGVy")
                .keyVersion((short) 1)
                .identifierCase(IdentifierCase.PRESERVE)
                .poolMaxSize(8)
                .poolMinIdle(2)
                .fetchSize(1000)
                .healthStatus("UNKNOWN")
                .build();
    }

    @Test
    @DisplayName("should_insertAndFind：新增后按 ID/名称都能查到")
    void should_insertAndFind() {
        DataSourceDao dao = new DataSourceDao(jdbc);
        assertThat(dao.insert(def(1L, "orders-prod"), "alice")).isEqualTo(1);
        DataSourceDef byId = dao.findById(1L).orElseThrow();
        assertThat(byId.getName()).isEqualTo("orders-prod");
        assertThat(byId.getEndpoint().getHost()).isEqualTo("127.0.0.1");
        assertThat(byId.getEndpoint().getPort()).isEqualTo(3306);
        assertThat(dao.findByName("orders-prod")).isPresent();
        assertThat(dao.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("should_updateWithOptimisticLock：版本匹配才更新，旧版本被拒")
    void should_updateWithOptimisticLock() {
        DataSourceDao dao = new DataSourceDao(jdbc);
        dao.insert(def(2L, "orders-uat"), "alice");
        DataSourceDef updated = DataSourceDef.builder(2L, "orders-uat2")
                .dbType(DbType.POSTGRESQL).env(EnvType.UAT)
                .endpoint(new JdbcEndpoint("10.0.0.9", 5432, "orders", "public", Map.of()))
                .username("u2").passwordCipher("c2").keyVersion((short) 2)
                .identifierCase(IdentifierCase.PRESERVE).poolMaxSize(4).poolMinIdle(1)
                .fetchSize(500).healthStatus("UP").build();
        assertThat(dao.update(updated, 0)).isEqualTo(1);
        assertThat(dao.update(updated, 0)).isZero();
        assertThat(dao.findById(2L).orElseThrow().getName()).isEqualTo("orders-uat2");
        assertThat(dao.findById(2L).orElseThrow().getDbType()).isEqualTo(DbType.POSTGRESQL);
    }

    @Test
    @DisplayName("should_softDeleteAndCountReferences：软删后查不到，被任务引用时可计数")
    void should_softDeleteAndCountReferences() {
        DataSourceDao dao = new DataSourceDao(jdbc);
        dao.insert(def(3L, "tmp-ds"), "alice");
        assertThat(dao.countTaskReferences(3L)).isZero();
        assertThat(dao.softDelete(3L)).isEqualTo(1);
        assertThat(dao.findById(3L)).isEmpty();
        assertThat(dao.findAll()).isEmpty();
    }

    @Test
    @DisplayName("should_updateHealth：探测结果回写")
    void should_updateHealth() {
        DataSourceDao dao = new DataSourceDao(jdbc);
        dao.insert(def(4L, "probe-ds"), "alice");
        assertThat(dao.updateHealth(4L, "DOWN", "连接超时 10s")).isEqualTo(1);
        assertThat(dao.findById(4L).orElseThrow().getHealthStatus()).isEqualTo("DOWN");
    }
}
