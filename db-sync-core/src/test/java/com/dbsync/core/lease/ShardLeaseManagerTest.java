package com.dbsync.core.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dbsync.common.enums.ClaimStrategy;
import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.enums.ShardState;
import com.dbsync.common.exception.LeaseLostException;
import com.dbsync.common.model.Shard;
import com.dbsync.common.util.SnowflakeId;
import com.dbsync.core.CoreTestSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T22 验收：租约接管、续租竞态、租约丢失必须让 worker 立刻停手。
 */
class ShardLeaseManagerTest extends CoreTestSupport {

    private static final long TASK_ID = 2002L;
    private long shardId;

    /**
     * 造一个 PENDING 分片。
     */
    @BeforeEach
    void setUpShard() {
        shardId = new SnowflakeId(2L).nextId();
        shardDao.insertBatch(List.of(new Shard(shardId, TASK_ID, 66L, 0, RangeKind.NUMERIC,
                "0", "1000", ShardState.PENDING, null, null, 0L, 0, 0)));
    }

    /**
     * 建管理器。
     *
     * @param strategy 策略
     * @param lease    租约
     * @return 管理器
     */
    private ShardLeaseManager manager(ClaimStrategy strategy, Duration lease) {
        return new ShardLeaseManager(shardDao, ShardLeaseManager.strategyOf(shardDao, strategy),
                lease, lease.dividedBy(3));
    }

    @Test
    @DisplayName("should_claimAndRenew：抢到后可续租，非持有者续租失败")
    void should_claimAndRenew() {
        ShardLeaseManager manager = manager(ClaimStrategy.SKIP_LOCKED, Duration.ofSeconds(30));
        Optional<LeaseHandle> handle = manager.claim(TASK_ID, "inst-A");
        assertThat(handle).isPresent();
        assertThat(handle.get().shard().getState()).isEqualTo(ShardState.CLAIMED);
        assertThat(handle.get().renew()).isTrue();
        assertThat(manager.renew(shardId, "inst-B")).isFalse();
        handle.get().assertHeld();
    }

    @Test
    @DisplayName("should_takeOverAfterLeaseExpired_When_ownerSilent：A 不续租 → 租约到期后 B 接管")
    void should_takeOverAfterLeaseExpired_When_ownerSilent() throws Exception {
        ShardLeaseManager manager = manager(ClaimStrategy.OPTIMISTIC_CAS, Duration.ofSeconds(1));
        assertThat(manager.claim(TASK_ID, "inst-A")).isPresent();
        // 等租约过期（1s + 100ms 余量）
        Thread.sleep(1200L);
        int reclaimed = manager.takeOverExpired(TASK_ID, "inst-B");
        assertThat(reclaimed).isEqualTo(1);
        Shard shard = shardDao.findById(shardId).orElseThrow();
        assertThat(shard.getLeaseOwner()).isEqualTo("inst-B");
        // 接管后 attempt 递增（架构验收：attempt +1）
        assertThat(shard.getAttempt()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("should_failTakeOver_When_ownerRenewedAtLastMoment：最后一刻续租则不被抢走")
    void should_failTakeOver_When_ownerRenewedAtLastMoment() throws Exception {
        ShardLeaseManager manager = manager(ClaimStrategy.OPTIMISTIC_CAS, Duration.ofSeconds(1));
        LeaseHandle handle = manager.claim(TASK_ID, "inst-A").orElseThrow();
        Thread.sleep(800L);
        // 到期前续租
        assertThat(handle.renew()).isTrue();
        int reclaimed = manager.takeOverExpired(TASK_ID, "inst-B");
        assertThat(reclaimed).isZero();
        assertThat(shardDao.findById(shardId).orElseThrow().getLeaseOwner()).isEqualTo("inst-A");
    }

    @Test
    @DisplayName("should_throwLeaseLost_When_ownerChanged：owner 变更后 assertHeld 抛 LeaseLostException")
    void should_throwLeaseLost_When_ownerChanged() {
        ShardLeaseManager manager = manager(ClaimStrategy.SKIP_LOCKED, Duration.ofSeconds(30));
        manager.claim(TASK_ID, "inst-A");
        jdbc.update("UPDATE sync_shard SET lease_owner=? WHERE id=?", "inst-B", shardId);
        assertThatThrownBy(() -> manager.assertHeld(shardId, "inst-A"))
                .isInstanceOf(LeaseLostException.class);
    }

    @Test
    @DisplayName("should_clampLeaseTo30s：配置 60s 被强制收敛为 30s（护住 ≤30s 接管承诺）")
    void should_clampLeaseTo30s() {
        ShardLeaseManager manager = manager(ClaimStrategy.SKIP_LOCKED, Duration.ofSeconds(60));
        assertThat(manager.leaseDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(manager.heartbeatInterval()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("should_releaseToPending：release 后回到 PENDING 且租约清空")
    void should_releaseToPending() {
        ShardLeaseManager manager = manager(ClaimStrategy.SKIP_LOCKED, Duration.ofSeconds(30));
        LeaseHandle handle = manager.claim(TASK_ID, "inst-A").orElseThrow();
        handle.close();
        Shard shard = shardDao.findById(shardId).orElseThrow();
        assertThat(shard.getState()).isEqualTo(ShardState.PENDING);
        assertThat(shard.getLeaseOwner()).isNull();
    }

    @Test
    @DisplayName("should_returnEmpty_When_noClaimableShard：无分片可抢时返回 empty（不空转异常）")
    void should_returnEmpty_When_noClaimableShard() {
        ShardLeaseManager manager = manager(ClaimStrategy.OPTIMISTIC_CAS, Duration.ofSeconds(30));
        assertThat(manager.claim(TASK_ID, "inst-A")).isPresent();
        assertThat(manager.claim(TASK_ID, "inst-B")).isEmpty();
    }

    @Test
    @DisplayName("should_coverAllShards_When_takeOverAndClaimShareSamePredicate：两条路径无死区")
    void should_coverAllShards_When_takeOverAndClaimShareSamePredicate() {
        // 先把 setUp 的分片置为成功，保证它不参与抢占
        jdbc.update("UPDATE sync_shard SET state=? WHERE id=?", "SUCCESS", shardId);
        // 造 5 个"已被 A 持有且租约已过期"的分片
        List<Shard> shards = new ArrayList<Shard>();
        long base = new SnowflakeId(3L).nextId();
        for (int i = 0; i < 5; i++) {
            shards.add(new Shard(base + i, TASK_ID, 66L, i + 10, RangeKind.NUMERIC, "0", "10",
                    ShardState.RUNNING, "inst-A", null, 0L, 1, 0));
        }
        shardDao.insertBatch(shards);
        jdbc.update("UPDATE sync_shard SET lease_expire_at=? WHERE task_id=?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(5)), TASK_ID);
        // takeOverExpired 必须能看到 claimOne 能看到的所有行（同一判据）
        int reclaimed = shardDao.takeOverExpired(TASK_ID, "inst-B");
        assertThat(reclaimed).isEqualTo(5); // 只有 5 个"CLAIMED/RUNNING 且租约过期"的行
        ShardLeaseManager manager = manager(ClaimStrategy.SKIP_LOCKED, Duration.ofSeconds(30));
        // 已被 B 持有（未过期）→ 现在抢不到
        assertThat(manager.claim(TASK_ID, "inst-C")).isEmpty();
    }
}
