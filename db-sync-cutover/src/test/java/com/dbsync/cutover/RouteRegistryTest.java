package com.dbsync.cutover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.enums.RouteTarget;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.RouteSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T38 验收：路由注册表（I6：routeVersion 单调 + 拒绝旧版本 ack）。
 */
class RouteRegistryTest {

    private static final long PLAN_ID = 42L;

    private final RouteRegistry registry = new RouteRegistry();

    /**
     * 构造快照。
     *
     * @param version 期望版本
     * @param read    读目标
     * @param ratio   读灰度
     * @param write   是否写目标
     * @return 快照
     */
    private static RouteSnapshot snapshot(long version, RouteTarget read, int ratio, boolean write) {
        return new RouteSnapshot(PLAN_ID, version, read, ratio, write, write ? 5 : 0, List.of(),
                Instant.now());
    }

    @Test
    @DisplayName("should_increaseVersion_When_publishTwice：版本严格递增")
    void should_increaseVersion_When_publishTwice() {
        long first = registry.publish(PLAN_ID, snapshot(0L, RouteTarget.SOURCE, 100, false));
        long second = registry.publish(PLAN_ID, snapshot(0L, RouteTarget.TARGET, 5, false));
        assertEquals(1L, first);
        assertEquals(2L, second);
        assertEquals(2L, registry.currentVersion(PLAN_ID));
        assertEquals(RouteTarget.TARGET, registry.current(PLAN_ID).getReadTarget());
    }

    @Test
    @DisplayName("should_ignoreStaleInputVersion_When_publishOldVersion：调用方传旧版本也不会回退")
    void should_ignoreStaleInputVersion_When_publishOldVersion() {
        registry.publish(PLAN_ID, snapshot(5L, RouteTarget.SOURCE, 100, false));
        long next = registry.publish(PLAN_ID, snapshot(1L, RouteTarget.TARGET, 5, false));
        assertEquals(6L, next, "必须取 max(期望, 当前+1)，否则 I6 被破坏");
    }

    @Test
    @DisplayName("should_rejectOldVersion_When_ackStale：旧版本 ack 必须拒绝")
    void should_rejectOldVersion_When_ackStale() {
        long v1 = registry.publish(PLAN_ID, snapshot(0L, RouteTarget.SOURCE, 100, false));
        long v2 = registry.publish(PLAN_ID, snapshot(0L, RouteTarget.TARGET, 5, false));

        DbSyncException e = assertThrows(DbSyncException.class, () -> registry.ack(PLAN_ID, v1));
        assertEquals(ErrorCode.ROUTE_ACK_TIMEOUT, e.errorCode());

        registry.ack(PLAN_ID, v2);
        assertEquals(v2, registry.ackedVersion(PLAN_ID));
    }

    @Test
    @DisplayName("should_countDownWaiter_When_ackArrives：awaitAck 能等到 ack")
    void should_countDownWaiter_When_ackArrives() throws Exception {
        long version = registry.publish(PLAN_ID, snapshot(0L, RouteTarget.TARGET, 5, false));
        Thread acker = new Thread(() -> {
            sleep(60L);
            registry.ack(PLAN_ID, version);
        });
        acker.start();

        boolean acked = registry.awaitAck(PLAN_ID, version, Duration.ofSeconds(2L));

        acker.join(2000L);
        assertTrue(acked);
    }

    /**
     * ack 与 waiter 登记并发时不得丢唤醒。
     *
     * <p>复现的是干净副本上偶发的 {@code TIMEOUT}：ack 恰好落在"首次检查 ackedVersion"
     * 与"登记 waiter"之间时，ack() 找不到 latch 就不会 countDown，旧实现会白等到超时。
     * 用放行栅栏让 ack 线程与 awaitAck 同时起跑、多轮放大该窗口；每轮用独立 planId，
     * 修复后每轮都应立即返回 true（不产生真实等待）。
     *
     * @throws InterruptedException 等待线程被中断
     */
    @Test
    @DisplayName("should_notMissAck_When_ackRacesWaiterRegistration：ack 与 waiter 登记并发不丢唤醒")
    void should_notMissAck_When_ackRacesWaiterRegistration() throws InterruptedException {
        int rounds = 80;
        for (int i = 0; i < rounds; i++) {
            long planId = 9_000_000L + i;
            long version = registry.publish(planId,
                    snapshot(0L, RouteTarget.TARGET, 5, false));
            CountDownLatch go = new CountDownLatch(1);
            Thread acker = new Thread(() -> {
                awaitQuietly(go);
                registry.ack(planId, version);
            }, "registry-acker-" + i);
            acker.setDaemon(true);
            acker.start();
            go.countDown();

            boolean acked = registry.awaitAck(planId, version, Duration.ofSeconds(2L));
            acker.join(2000L);
            assertTrue(acked, "第 " + i + " 轮：ack 已到达，awaitAck 不得误判为超时");
        }
    }

    @Test
    @DisplayName("should_timeout_When_noAck：没人 ack 时按超时返回 false")
    void should_timeout_When_noAck() {
        long version = registry.publish(PLAN_ID, snapshot(0L, RouteTarget.TARGET, 5, false));
        long start = System.currentTimeMillis();
        boolean acked = registry.awaitAck(PLAN_ID, version, Duration.ofMillis(200L));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(!acked);
        assertTrue(elapsed >= 150L, "必须真的等满超时，实际 " + elapsed + "ms");
    }

    @Test
    @DisplayName("should_returnNull_When_neverPublished：未发布返回 null/0")
    void should_returnNull_When_neverPublished() {
        assertNull(registry.current(999L));
        assertEquals(0L, registry.currentVersion(999L));
        assertEquals(0L, registry.ackedVersion(999L));
    }

    @Test
    @DisplayName("should_throw_When_ackWithoutPublish：未发布就 ack 报错")
    void should_throw_When_ackWithoutPublish() {
        assertThrows(DbSyncException.class, () -> registry.ack(777L, 1L));
    }

    @Test
    @DisplayName("should_mapBucket_When_snapshotGiven：快照形态折叠正确")
    void should_mapBucket_When_snapshotGiven() {
        assertEquals(RouteRegistry.Bucket.SOURCE_ONLY, RouteRegistry.bucketOf(
                RouteSnapshot.fullSource(PLAN_ID, 1L, Instant.now())));
        assertEquals(RouteRegistry.Bucket.GRAY_READ,
                RouteRegistry.bucketOf(snapshot(2L, RouteTarget.TARGET, 5, false)));
        assertEquals(RouteRegistry.Bucket.GRAY_WRITE,
                RouteRegistry.bucketOf(snapshot(3L, RouteTarget.TARGET, 100, true)));
        assertEquals(RouteRegistry.Bucket.TARGET_FULL,
                RouteRegistry.bucketOf(new RouteSnapshot(PLAN_ID, 4L, RouteTarget.TARGET, 100, true,
                        100, List.of(), Instant.now())));
        assertEquals(RouteRegistry.Bucket.SOURCE_ONLY, RouteRegistry.bucketOf((RouteSnapshot) null));
    }

    /**
     * 等待放行栅栏（被中断时恢复中断标志，不吞异常状态）。
     *
     * @param latch 栅栏
     */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 可中断休眠。
     *
     * @param millis 毫秒
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
