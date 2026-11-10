package com.dbsync.metadata.dao;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.enums.ClaimStrategy;
import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.enums.ShardState;
import com.dbsync.common.model.Shard;
import com.dbsync.common.model.ShardRowSums;
import com.dbsync.common.util.SnowflakeId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T15 验收（最关键的一组）：分片抢占不重复、租约过期可接管、状态统计正确。
 */
class ShardDaoTest extends MetaDaoTestSupport {

    private static final long TASK_ID = 9001L;

    /**
     * 插入 N 个 PENDING 分片。
     *
     * @param dao 分片 DAO
     * @param n   数量
     */
    private void seedShards(ShardDao dao, int n) {
        List<Shard> shards = new ArrayList<Shard>(n);
        long base = new SnowflakeId(1L).nextId();
        for (int i = 0; i < n; i++) {
            shards.add(new Shard(base + i, TASK_ID, 100L + (i % 3), i, RangeKind.NUMERIC,
                    String.valueOf(i * 1000L), String.valueOf((i + 1) * 1000L - 1),
                    ShardState.PENDING, null, null, 0L, 0, 0));
        }
        assertThat(dao.insertBatch(shards)).isEqualTo(n);
    }

    /**
     * 两线程抢 N 个分片，返回抢到的 ID 列表。
     *
     * @param dao 分片 DAO
     * @param n   分片数
     * @return 抢到的 ID
     * @throws InterruptedException 线程中断
     */
    private List<Long> raceClaim(ShardDao dao, int n) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Long> claimed = Collections.synchronizedList(new ArrayList<Long>());
        AtomicInteger finished = new AtomicInteger();
        for (int t = 0; t < 2; t++) {
            final String owner = "inst-" + t;
            pool.submit(() -> {
                try {
                    start.await();
                    while (true) {
                        Optional<Shard> shard = dao.claimOne(TASK_ID, owner, Duration.ofSeconds(30));
                        if (shard.isEmpty()) {
                            break;
                        }
                        claimed.add(Long.valueOf(shard.get().getId()));
                    }
                    finished.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        assertThat(finished.get()).isEqualTo(2);
        return claimed;
    }

    @Test
    @DisplayName("should_claimDistinctShards_When_twoThreadsRace_SKIP_LOCKED")
    void should_claimDistinctShards_When_twoThreadsRace_skipLocked() throws InterruptedException {
        ShardDao dao = new ShardDao(jdbc, tx, ClaimStrategy.SKIP_LOCKED);
        seedShards(dao, 100);
        List<Long> claimed = raceClaim(dao, 100);
        Set<Long> unique = new HashSet<Long>(claimed);
        assertThat(claimed).hasSize(100);
        assertThat(unique).hasSize(100);
    }

    @Test
    @DisplayName("should_claimDistinctShards_When_twoThreadsRace_OPTIMISTIC_CAS")
    void should_claimDistinctShards_When_twoThreadsRace_optimisticCas() throws InterruptedException {
        ShardDao dao = new ShardDao(jdbc, tx, ClaimStrategy.OPTIMISTIC_CAS);
        seedShards(dao, 100);
        List<Long> claimed = raceClaim(dao, 100);
        assertThat(claimed).hasSize(100);
        assertThat(new HashSet<Long>(claimed)).hasSize(100);
        // 抢完后全库无 PENDING
        assertThat(dao.countByState(TASK_ID).get(ShardState.PENDING)).isZero();
        assertThat(dao.countByState(TASK_ID).get(ShardState.CLAIMED)).isEqualTo(100);
    }

    @Test
    @DisplayName("should_takeOver_When_leaseExpired：过期租约被回收且 owner 变更")
    void should_takeOver_When_leaseExpired() {
        ShardDao dao = new ShardDao(jdbc, tx, ClaimStrategy.SKIP_LOCKED);
        seedShards(dao, 1);
        Shard shard = dao.findByTask(TASK_ID).get(0);
        // 制造"实例 A 抢到但已宕机"：lease_expire_at = now - 1s
        jdbc.update("UPDATE sync_shard SET state=?,lease_owner=?,lease_expire_at=?,version=version+1 "
                        + "WHERE id=?", ShardState.RUNNING.name(), "inst-A",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)), shard.getId());
        int reclaimed = dao.takeOverExpired(TASK_ID, "inst-B");
        assertThat(reclaimed).isEqualTo(1);
        Shard after = dao.findById(shard.getId()).orElseThrow();
        assertThat(after.getLeaseOwner()).isEqualTo("inst-B");
    }

    @Test
    @DisplayName("should_notTakeOver_When_leaseAlive：未过期租约不被回收")
    void should_notTakeOver_When_leaseAlive() {
        ShardDao dao = new ShardDao(jdbc, tx, ClaimStrategy.SKIP_LOCKED);
        seedShards(dao, 1);
        assertThat(dao.claimOne(TASK_ID, "inst-A", Duration.ofSeconds(30))).isPresent();
        assertThat(dao.takeOverExpired(TASK_ID, "inst-B")).isZero();
    }

    @Test
    @DisplayName("should_renewAndRelease：续租成功、释放后回到 PENDING")
    void should_renewAndRelease() {
        ShardDao dao = new ShardDao(jdbc, tx, ClaimStrategy.SKIP_LOCKED);
        seedShards(dao, 1);
        Shard shard = dao.claimOne(TASK_ID, "inst-A", Duration.ofSeconds(30)).orElseThrow();
        assertThat(dao.renewLease(shard.getId(), "inst-A", Duration.ofSeconds(30))).isTrue();
        assertThat(dao.renewLease(shard.getId(), "inst-B", Duration.ofSeconds(30))).isFalse();
        assertThat(dao.assertHeld(shard.getId(), "inst-A")).isTrue();
        dao.release(shard.getId(), "inst-A");
        assertThat(dao.findById(shard.getId()).orElseThrow().getState())
                .isEqualTo(ShardState.PENDING);
    }

    @Test
    @DisplayName("should_countByState：六个状态键全部存在且缺省 0")
    void should_countByState() {
        ShardDao dao = new ShardDao(jdbc, tx, ClaimStrategy.SKIP_LOCKED);
        seedShards(dao, 5);
        Map<ShardState, Integer> counts = dao.countByState(TASK_ID);
        assertThat(counts).containsKeys(ShardState.PENDING, ShardState.CLAIMED, ShardState.RUNNING,
                ShardState.SUCCESS, ShardState.FAILED, ShardState.DEAD);
        assertThat(counts.get(ShardState.PENDING)).isEqualTo(5);
        assertThat(counts.get(ShardState.SUCCESS)).isZero();
    }

    @Test
    @DisplayName("should_updateProgressWithCas：版本不符则更新 0 行（防丢租约后继续写）")
    void should_updateProgressWithCas() {
        ShardDao dao = new ShardDao(jdbc, tx, ClaimStrategy.SKIP_LOCKED);
        seedShards(dao, 1);
        Shard shard = dao.claimOne(TASK_ID, "inst-A", Duration.ofSeconds(30)).orElseThrow();
        assertThat(dao.updateProgress(shard.getId(), "inst-A", "500", 500L, shard.getVersion()))
                .isEqualTo(1);
        // 用旧版本再更新 → 0 行，调用方据此停止
        assertThat(dao.updateProgress(shard.getId(), "inst-A", "900", 900L, shard.getVersion()))
                .isZero();
        // 非持有者更新 → 0 行
        Shard fresh = dao.findById(shard.getId()).orElseThrow();
        assertThat(dao.updateProgress(shard.getId(), "inst-B", "900", 900L, fresh.getVersion()))
                .isZero();
    }

    @Test
    @DisplayName("markSuccess(4 参)：把实际读取行数写进 source_rows（按表进度的分母）")
    void should_writeSourceRows_When_markSuccessWithFourArgs() {
        ShardDao dao = new ShardDao(jdbc, tx, ClaimStrategy.SKIP_LOCKED);
        seedShards(dao, 1);
        Shard shard = dao.claimOne(TASK_ID, "inst-A", Duration.ofSeconds(30)).orElseThrow();

        // 无主键降级表：读到 11 行、真正落库 8 行 → 两个数必须分开写
        assertThat(dao.markSuccess(shard.getId(), "inst-A", 8L, 11L)).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT state,committed_rows,source_rows,lease_owner FROM sync_shard WHERE id=?",
                shard.getId());
        assertThat(row.get("state")).isEqualTo(ShardState.SUCCESS.name());
        assertThat(((Number) row.get("committed_rows")).longValue()).isEqualTo(8L);
        assertThat(((Number) row.get("source_rows")).longValue()).isEqualTo(11L);
        assertThat(row.get("lease_owner")).isNull();
    }

    @Test
    @DisplayName("markSuccess(3 参)：兼容旧调用，source_rows 取 committed_rows")
    void should_defaultSourceRowsToCommitted_When_markSuccessWithThreeArgs() {
        ShardDao dao = new ShardDao(jdbc, tx, ClaimStrategy.SKIP_LOCKED);
        seedShards(dao, 1);
        Shard shard = dao.claimOne(TASK_ID, "inst-A", Duration.ofSeconds(30)).orElseThrow();

        assertThat(dao.markSuccess(shard.getId(), "inst-A", 42L)).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT committed_rows,source_rows FROM sync_shard WHERE id=?", shard.getId());
        assertThat(((Number) row.get("committed_rows")).longValue()).isEqualTo(42L);
        assertThat(((Number) row.get("source_rows")).longValue()).isEqualTo(42L);
    }

    @Test
    @DisplayName("sumRowsGroupByMapping：默认 -1（未知）的分片不计入源行数合计")
    void should_ignoreUnknownSourceRows_When_summing() {
        ShardDao dao = new ShardDao(jdbc, tx, ClaimStrategy.SKIP_LOCKED);
        // 每个 table_mapping_id 各 2 个分片（100 / 101 / 102）
        seedShards(dao, 6);
        Long firstOf100 = jdbc.queryForObject(
                "SELECT MIN(id) FROM sync_shard WHERE table_mapping_id=?", Long.class, 100L);
        jdbc.update("UPDATE sync_shard SET committed_rows=?,source_rows=? WHERE id=?",
                400L, 500L, firstOf100);

        Map<Long, ShardRowSums> sums = dao.sumRowsGroupByMapping(TASK_ID);

        assertThat(sums.get(100L).getCommittedRows()).isEqualTo(400L);
        // 另一个分片是默认 -1，不能被累加（否则会算成 499）
        assertThat(sums.get(100L).getSourceRows()).isEqualTo(500L);
        // 全部未知 → 聚合结果 0，调用方据此退回估算值
        assertThat(sums.get(101L).getSourceRows()).isZero();
    }
}
