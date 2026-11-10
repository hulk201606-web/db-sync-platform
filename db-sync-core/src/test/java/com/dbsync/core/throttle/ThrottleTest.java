package com.dbsync.core.throttle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T23 验收：限速生效、不空转、可运行时调整。
 */
class ThrottleTest {

    @Test
    @DisplayName("should_limitTo10000_When_rateSet10000：1s 内累计获批 ≤ 10500")
    void should_limitTo10000_When_rateSet10000() {
        Throttle throttle = new Throttle(10_000L, 0L);
        // 令牌桶初始是满的：先吃掉初始突发，再测稳态速率
        throttle.acquireReadRows(10_000);
        long start = System.currentTimeMillis();
        long granted = 0;
        while (System.currentTimeMillis() - start < 1000L) {
            granted += throttle.acquireReadRows(500);
        }
        long elapsed = System.currentTimeMillis() - start;
        // 令牌桶初始是满的（容量=速率），首秒允许 initial burst + refill，容差 5%
        assertThat(granted).isLessThanOrEqualTo(10_500L);
        assertThat(granted).isGreaterThan(5_000L);
        assertThat(elapsed).isGreaterThanOrEqualTo(1000L);
    }

    @Test
    @DisplayName("should_blockNotSpin_When_noPermit：无许可时阻塞而不是返回 0 空转")
    void should_blockNotSpin_When_noPermit() {
        Throttle throttle = new Throttle(10L, 10L);
        // 先把前 1s 的许可耗掉（容量 10）
        for (int i = 0; i < 20; i++) {
            throttle.acquireReadRows(1);
        }
        long before = System.nanoTime();
        int granted = throttle.acquireReadRows(1);
        long elapsedMs = (System.nanoTime() - before) / 1_000_000L;
        // 必须真的等到下一批令牌（10/s → 至少约 100ms），且返回值 ≥ 1
        assertThat(granted).isGreaterThanOrEqualTo(1);
        assertThat(elapsedMs).isGreaterThanOrEqualTo(50L);
    }

    @Test
    @DisplayName("should_takeEffectWithin1s_When_updateLimits：降到 10/s 后立刻变慢")
    void should_takeEffectWithin1s_When_updateLimits() {
        Throttle throttle = new Throttle(1_000_000L, 0L);
        assertThat(throttle.readLimit()).isEqualTo(1_000_000L);
        throttle.updateLimits(10L, 0L);
        assertThat(throttle.readLimit()).isEqualTo(10L);
        // 先吃掉 updateRate 后残余的令牌（≤新速率），再验证"确实被卡住"
        throttle.acquireReadRows(10);
        long before = System.nanoTime();
        throttle.acquireReadRows(100);
        long elapsedMs = (System.nanoTime() - before) / 1_000_000L;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(100L);
    }

    @Test
    @DisplayName("should_notLimit_When_rateIsZero：0 表示不限速，直接放行")
    void should_notLimit_When_rateIsZero() {
        Throttle throttle = Throttle.unlimited();
        long before = System.currentTimeMillis();
        int granted = throttle.acquireReadRows(100_000);
        long elapsed = System.currentTimeMillis() - before;
        assertThat(granted).isEqualTo(100_000);
        assertThat(elapsed).isLessThan(100L);
        assertThat(throttle.readLimit()).isZero();
        assertThat(throttle.writeLimit()).isZero();
    }

    @Test
    @DisplayName("should_returnZero_When_requestedNonPositive：非法请求返回 0 不阻塞")
    void should_returnZero_When_requestedNonPositive() {
        Throttle throttle = new Throttle(100L, 100L);
        assertThat(throttle.acquireReadRows(0)).isZero();
        assertThat(throttle.acquireWriteRows(-5)).isZero();
    }

    @Test
    @DisplayName("should_limitReadAndWriteSeparately：读限速不拖住写")
    void should_limitReadAndWriteSeparately() {
        Throttle throttle = new Throttle(1L, 1_000_000L);
        long before = System.currentTimeMillis();
        throttle.acquireWriteRows(50_000);
        assertThat(System.currentTimeMillis() - before).isLessThan(100L);
        assertThat(throttle.writeLimit()).isEqualTo(1_000_000L);
        assertThat(throttle.readLimit()).isEqualTo(1L);
    }
}
