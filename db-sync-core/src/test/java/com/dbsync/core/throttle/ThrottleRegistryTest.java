package com.dbsync.core.throttle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MIG-05 验收：限速器注册表（同一 taskId 必须是同一个实例，否则热更新打在空气上）。
 */
class ThrottleRegistryTest {

    @Test
    @DisplayName("should_returnSameInstance_When_acquireTwice：同一 taskId 两次 acquire 是同一对象")
    void should_returnSameInstance_When_acquireTwice() {
        ThrottleRegistry registry = new ThrottleRegistry();
        Throttle first = registry.acquire(7L, 5_000L, 5_000L);
        Throttle second = registry.acquire(7L, 5_000L, 5_000L);

        assertThat(second).isSameAs(first);
        assertThat(registry.activeTaskCount()).isEqualTo(1L);
        assertThat(first.readLimit()).isEqualTo(5_000L);
        assertThat(first.writeLimit()).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("should_keepFirstLimits_When_acquireAgainWithOtherLimits：已存在时忽略入参，防止悄悄换对象")
    void should_keepFirstLimits_When_acquireAgainWithOtherLimits() {
        ThrottleRegistry registry = new ThrottleRegistry();
        Throttle first = registry.acquire(8L, 1_000L, 1_000L);
        Throttle again = registry.acquire(8L, 9_999L, 9_999L);

        assertThat(again).isSameAs(first);
        assertThat(again.readLimit()).isEqualTo(1_000L);
        assertThat(again.writeLimit()).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("should_returnNewInstance_When_acquireAfterRemove：注销后重新 acquire 是新实例")
    void should_returnNewInstance_When_acquireAfterRemove() {
        ThrottleRegistry registry = new ThrottleRegistry();
        Throttle first = registry.acquire(9L, 1_000L, 1_000L);
        registry.remove(9L);
        assertThat(registry.find(9L)).isEmpty();
        assertThat(registry.activeTaskCount()).isZero();

        Throttle second = registry.acquire(9L, 2_000L, 3_000L);

        assertThat(second).isNotSameAs(first);
        assertThat(second.readLimit()).isEqualTo(2_000L);
        assertThat(second.writeLimit()).isEqualTo(3_000L);
        assertThat(registry.activeTaskCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("should_createOnlyOneInstance_When_concurrentAcquire：并发抢占只产出一个实例")
    void should_createOnlyOneInstance_When_concurrentAcquire() throws Exception {
        ThrottleRegistry registry = new ThrottleRegistry();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(threads);
        Set<Throttle> seen = ConcurrentHashMap.newKeySet();
        List<Throwable> errors = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        seen.add(registry.acquire(42L, 1_000L, 1_000L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException e) {
                        synchronized (errors) {
                            errors.add(e);
                        }
                    }
                });
            }
            assertThat(ready.await(5L, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5L, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors).isEmpty();
        assertThat(seen).hasSize(1);
        assertThat(registry.activeTaskCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("should_beNoop_When_removeUnknownTask：注销不存在的任务不抛异常")
    void should_beNoop_When_removeUnknownTask() {
        ThrottleRegistry registry = new ThrottleRegistry();
        registry.remove(123L);
        assertThat(registry.activeTaskCount()).isZero();
        assertThat(registry.find(123L)).isEmpty();
    }

    @Test
    @DisplayName("should_applyLimitImmediately_When_liveThrottleUpdated：注册表里的实例被热更新")
    void should_applyLimitImmediately_When_liveThrottleUpdated() {
        ThrottleRegistry registry = new ThrottleRegistry();
        registry.acquire(55L, 5_000L, 5_000L);

        Throttle live = registry.find(55L).orElseThrow();
        live.updateLimits(10_000L, 10_000L);

        assertThat(live.readLimit()).isEqualTo(10_000L);
        assertThat(registry.find(55L).orElseThrow().readLimit()).isEqualTo(10_000L);
    }
}
