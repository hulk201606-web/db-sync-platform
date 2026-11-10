package com.dbsync.metadata.dao;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.model.InstanceInfo;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 实例心跳 DAO 测试：注册幂等、心跳刷新、僵尸清理。
 */
class InstanceDaoTest extends MetaDaoTestSupport {

    /**
     * 造一个实例。
     *
     * @param id 实例 ID
     * @return 实例信息
     */
    private InstanceInfo instance(String id) {
        Instant now = Instant.now();
        return new InstanceInfo(id, "10.0.0.11", 8080, "1.0.0", "UP", now, now, 0);
    }

    @Test
    @DisplayName("should_registerIdempotently：重复注册不产生第二行")
    void should_registerIdempotently() {
        InstanceDao dao = new InstanceDao(jdbc);
        assertThat(dao.register(instance("host1:8080#uuid1"))).isEqualTo(1);
        assertThat(dao.register(instance("host1:8080#uuid1"))).isEqualTo(1);
        assertThat(dao.listAll()).hasSize(1);
        assertThat(dao.findById("host1:8080#uuid1").orElseThrow().getHost()).isEqualTo("10.0.0.11");
    }

    @Test
    @DisplayName("should_heartbeatAndListAlive：心跳后进入活跃列表，超时后离开")
    void should_heartbeatAndListAlive() {
        InstanceDao dao = new InstanceDao(jdbc);
        dao.register(instance("inst-A"));
        dao.register(instance("inst-B"));
        assertThat(dao.heartbeat("inst-A", 3, 1)).isEqualTo(1);
        assertThat(dao.listAlive(Duration.ofMinutes(1))).hasSize(2);
        // 把 B 的心跳推到 10 分钟前 → 不算活跃
        jdbc.update("UPDATE sys_instance SET heartbeat_at=? WHERE id='inst-B'",
                java.sql.Timestamp.from(Instant.now().minusSeconds(600)));
        assertThat(dao.listAlive(Duration.ofMinutes(1))).hasSize(1);
        assertThat(dao.countAlive(Duration.ofMinutes(1))).isEqualTo(1L);
    }

    @Test
    @DisplayName("should_removeStaleAndDeregister：僵尸行可清理，优雅停机可注销")
    void should_removeStaleAndDeregister() {
        InstanceDao dao = new InstanceDao(jdbc);
        dao.register(instance("inst-C"));
        dao.register(instance("inst-D"));
        jdbc.update("UPDATE sys_instance SET heartbeat_at=? WHERE id='inst-D'",
                java.sql.Timestamp.from(Instant.now().minusSeconds(3600)));
        assertThat(dao.removeStale(Duration.ofMinutes(5))).isEqualTo(1);
        assertThat(dao.listAll()).hasSize(1);
        assertThat(dao.deregister("inst-C")).isEqualTo(1);
        assertThat(dao.listAll()).isEmpty();
    }
}
