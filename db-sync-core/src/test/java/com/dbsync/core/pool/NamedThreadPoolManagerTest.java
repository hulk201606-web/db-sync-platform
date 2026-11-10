package com.dbsync.core.pool;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T23 验收：线程命名可辨识（jstack 一眼定位任务）+ 优雅停机在 await 内结束。
 */
class NamedThreadPoolManagerTest {

    @Test
    @DisplayName("should_nameThreadsWithTaskId：线程名含 task-{id}-w")
    void should_nameThreadsWithTaskId() throws Exception {
        NamedThreadPoolManager manager = new NamedThreadPoolManager();
        ExecutorService pool = manager.migrationPool(42L, 2);
        AtomicReference<String> name = new AtomicReference<String>();
        CountDownLatch latch = new CountDownLatch(1);
        pool.submit(() -> {
            name.set(Thread.currentThread().getName());
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(name.get()).startsWith("task-42-w");
        manager.shutdownAll(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("should_nameCdcThreads：CDC 池线程名含 cdc-{id}-g")
    void should_nameCdcThreads() throws Exception {
        NamedThreadPoolManager manager = new NamedThreadPoolManager();
        ExecutorService pool = manager.cdcPool(7L, 1);
        AtomicReference<String> name = new AtomicReference<String>();
        CountDownLatch latch = new CountDownLatch(1);
        pool.submit(() -> {
            name.set(Thread.currentThread().getName());
            latch.countDown();
        });
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(name.get()).startsWith("cdc-7-g");
        manager.shutdownAll(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("should_reuseSamePool_When_calledTwice：同 taskId 复用同一个池（不重复建线程）")
    void should_reuseSamePool_When_calledTwice() {
        NamedThreadPoolManager manager = new NamedThreadPoolManager();
        ExecutorService first = manager.migrationPool(1L, 4);
        ExecutorService second = manager.migrationPool(1L, 4);
        assertThat(second).isSameAs(first);
        assertThat(manager.migrationPoolCount()).isEqualTo(1);
        manager.shutdownAll(Duration.ofSeconds(5));
        assertThat(manager.migrationPoolCount()).isZero();
    }

    @Test
    @DisplayName("should_shutdownWithinAwait：停机在 await 内完成，任务能跑完")
    void should_shutdownWithinAwait() throws Exception {
        NamedThreadPoolManager manager = new NamedThreadPoolManager();
        ExecutorService pool = manager.migrationPool(9L, 1);
        CountDownLatch finished = new CountDownLatch(1);
        pool.submit(() -> {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.countDown();
        });
        manager.shutdown(9L, Duration.ofSeconds(5));
        assertThat(finished.getCount()).isZero();
        assertThat(pool.isShutdown()).isTrue();
    }

    @Test
    @DisplayName("should_clampConcurrency：并发度收敛到 1..64")
    void should_clampConcurrency() throws Exception {
        NamedThreadPoolManager manager = new NamedThreadPoolManager();
        ExecutorService pool = manager.migrationPool(11L, 0);
        // 并发度 0 被收敛为 1：同时只应有一个任务在跑
        CountDownLatch firstRunning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        pool.submit(() -> {
            firstRunning.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(firstRunning.await(2, TimeUnit.SECONDS)).isTrue();
        pool.submit(secondStarted::countDown);
        // 单线程池：第二个任务在第一个释放之前不会开始
        assertThat(secondStarted.await(300, TimeUnit.MILLISECONDS)).isFalse();
        release.countDown();
        assertThat(secondStarted.await(3, TimeUnit.SECONDS)).isTrue();
        manager.shutdownAll(Duration.ofSeconds(5));
    }
}
