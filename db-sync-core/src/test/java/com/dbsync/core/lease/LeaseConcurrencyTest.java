package com.dbsync.core.lease;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.enums.ClaimStrategy;
import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.enums.ShardState;
import com.dbsync.common.model.Shard;
import com.dbsync.common.util.SnowflakeId;
import com.dbsync.core.CoreTestSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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
 * T22 验收（多实例正确性）：2 线程抢 100 个分片，三策略各跑一遍，结果必须无重复、无遗漏。
 */
class LeaseConcurrencyTest extends CoreTestSupport {

    private static final long TASK_ID = 3003L;

    /**
     * 插入 100 个分片。
     */
    private void seed() {
        List<Shard> shards = new ArrayList<Shard>(100);
        long base = new SnowflakeId(4L).nextId();
        for (int i = 0; i < 100; i++) {
            shards.add(new Shard(base + i, TASK_ID, 77L, i, RangeKind.NUMERIC,
                    String.valueOf(i * 1000L), String.valueOf((i + 1) * 1000L), ShardState.PENDING,
                    null, null, 0L, 0, 0));
        }
        assertThat(shardDao.insertBatch(shards)).isEqualTo(100);
    }

    /**
     * 两线程并发抢占。
     *
     * @param strategy 策略
     * @return 抢到的分片 ID 列表
     * @throws InterruptedException 中断
     */
    private List<Long> race(ClaimStrategy strategy) throws InterruptedException {
        ShardLeaseManager manager = new ShardLeaseManager(shardDao,
                ShardLeaseManager.strategyOf(shardDao, strategy), Duration.ofSeconds(30),
                Duration.ofSeconds(10));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Long> claimed = Collections.synchronizedList(new ArrayList<Long>());
        AtomicInteger done = new AtomicInteger();
        for (int t = 0; t < 2; t++) {
            final String owner = "inst-" + t;
            pool.submit(() -> {
                try {
                    start.await();
                    while (true) {
                        Optional<LeaseHandle> handle = manager.claim(TASK_ID, owner);
                        if (handle.isEmpty()) {
                            break;
                        }
                        claimed.add(Long.valueOf(handle.get().shardId()));
                    }
                    done.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
        assertThat(done.get()).isEqualTo(2);
        return claimed;
    }

    @Test
    @DisplayName("should_notDuplicate_When_twoThreadsClaim100Shards_SKIP_LOCKED")
    void should_notDuplicate_skipLocked() throws InterruptedException {
        seed();
        List<Long> claimed = race(ClaimStrategy.SKIP_LOCKED);
        Set<Long> unique = new HashSet<Long>(claimed);
        assertThat(claimed).hasSize(100);
        assertThat(unique).hasSize(100);
    }

    @Test
    @DisplayName("should_notDuplicate_When_twoThreadsClaim100Shards_NOWAIT_PROBE")
    void should_notDuplicate_noWaitProbe() throws InterruptedException {
        seed();
        List<Long> claimed = race(ClaimStrategy.NOWAIT_PROBE);
        assertThat(claimed).hasSize(100);
        assertThat(new HashSet<Long>(claimed)).hasSize(100);
    }

    @Test
    @DisplayName("should_notDuplicate_When_twoThreadsClaim100Shards_OPTIMISTIC_CAS")
    void should_notDuplicate_optimisticCas() throws InterruptedException {
        seed();
        List<Long> claimed = race(ClaimStrategy.OPTIMISTIC_CAS);
        assertThat(claimed).hasSize(100);
        assertThat(new HashSet<Long>(claimed)).hasSize(100);
        // 全部 100 片都被抢走，没有 PENDING 残留
        assertThat(shardDao.countByState(TASK_ID).get(ShardState.PENDING)).isZero();
    }
}
