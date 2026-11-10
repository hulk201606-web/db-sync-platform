package com.dbsync.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 限速器 / 重试 / 标识符 / 哈希 / 雪花 ID 单测。
 */
class CommonUtilTest {

    @Test
    @DisplayName("稳态 1 秒内累计获批不超过 1050（1000/s，5% 容差）")
    void should_limitTo1000_When_rateSet1000() {
        RateLimiter limiter = new RateLimiter(1000);
        // 先耗尽初始突发许可，再测量稳态速率（符合"每秒许可 ≤ rate"的定义）
        limiter.tryAcquireRows(1000);
        long total = 0;
        long deadline = System.currentTimeMillis() + 1000;
        while (System.currentTimeMillis() < deadline) {
            long grant = limiter.tryAcquireRows(10);
            total += grant;
            if (grant == 0) {
                Thread.onSpinWait();
            }
        }
        assertThat(total).isLessThanOrEqualTo(1050L);
        assertThat(total).isGreaterThan(0L);
    }

    @Test
    @DisplayName("不限速时原样放行")
    void should_passThrough_When_rateZero() {
        RateLimiter limiter = new RateLimiter(0);
        assertThat(limiter.tryAcquireRows(12345)).isEqualTo(12345L);
    }

    @Test
    @DisplayName("无许可时阻塞而非返回 0")
    void should_blockNotSpin_When_noPermit() {
        RateLimiter limiter = new RateLimiter(20);
        assertThat(limiter.tryAcquireRows(1000)).isEqualTo(20L);
        long start = System.nanoTime();
        int grant = limiter.acquireBlocking(10);
        long costMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(grant).isGreaterThanOrEqualTo(1);
        assertThat(costMs).isGreaterThanOrEqualTo(10L);
    }

    @Test
    @DisplayName("updateRate 后 1s 内生效")
    void should_takeEffect_When_updateRate() {
        RateLimiter limiter = new RateLimiter(100);
        assertThat(limiter.tryAcquireRows(1000)).isLessThanOrEqualTo(100L);
        limiter.updateRate(10_000);
        long total = 0;
        long deadline = System.currentTimeMillis() + 500;
        while (System.currentTimeMillis() < deadline) {
            total += limiter.tryAcquireRows(100);
        }
        assertThat(total).isGreaterThan(100L);
        assertThat(limiter.permitsPerSecond()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("不可重试异常只执行 1 次")
    void should_executeOnce_When_notRetryable() {
        RetryTemplate retry = new RetryTemplate(3, Duration.ofMillis(1), 2.0d, 0.0d);
        AtomicInteger counter = new AtomicInteger();
        try {
            retry.execute(() -> {
                counter.incrementAndGet();
                throw new IllegalStateException("不可重试");
            }, t -> false);
        } catch (RuntimeException ignored) {
            // 期望抛出
        }
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("可重试异常执行满 maxAttempts 次")
    void should_executeMaxAttempts_When_retryable() {
        RetryTemplate retry = new RetryTemplate(3, Duration.ofMillis(5), 2.0d, 0.2d);
        AtomicInteger counter = new AtomicInteger();
        try {
            retry.execute(() -> {
                counter.incrementAndGet();
                throw new IllegalStateException("可重试");
            }, t -> true);
        } catch (RuntimeException ignored) {
            // 期望抛出 E1020
        }
        assertThat(counter.get()).isEqualTo(3);
        assertThat(retry.backoffOf(1).toMillis()).isLessThan(retry.backoffOf(3).toMillis());
    }

    @Test
    @DisplayName("标识符白名单：拒绝注入/引号/注释/超长，接受合法名")
    void should_validateIdentifier_When_sqlInjectionAttempted() {
        assertThat(IdentifierValidator.isValid("t; DROP TABLE x")).isFalse();
        assertThat(IdentifierValidator.isValid("\"t\"")).isFalse();
        assertThat(IdentifierValidator.isValid("t--")).isFalse();
        assertThat(IdentifierValidator.isValid("T_ORDER")).isTrue();
        assertThat(IdentifierValidator.isValid("t$1")).isTrue();
        assertThat(IdentifierValidator.isValid("_tmp")).isTrue();
        assertThat(IdentifierValidator.isValid("1abc")).isFalse();
        assertThat(IdentifierValidator.isValid(null)).isFalse();
        com.dbsync.common.exception.DbSyncException invalid = org.assertj.core.api.Assertions
                .catchThrowableOfType(
                        () -> IdentifierValidator.validate("t; DROP TABLE x", 64, null),
                        com.dbsync.common.exception.DbSyncException.class);
        assertThat(invalid.errorCode().code()).isEqualTo("E1002");
        com.dbsync.common.exception.DbSyncException tooLong = org.assertj.core.api.Assertions
                .catchThrowableOfType(
                        () -> IdentifierValidator.validate("THIS_IDENTIFIER_NAME_IS_TOO_LONG_FOR_ORACLE_11G",
                                30, com.dbsync.common.enums.DbType.ORACLE),
                        com.dbsync.common.exception.DbSyncException.class);
        assertThat(tooLong.errorCode().code()).isEqualTo("E7003");
    }

    @Test
    @DisplayName("fnv1a64 稳定；pkHash 对 1L 与 BigDecimal(\"1.00\") 相同")
    void should_hashSameValue_When_numericTypesDiffer() {
        assertThat(HashUtil.fnv1a64("T_ORDER#1")).isEqualTo(HashUtil.fnv1a64("T_ORDER#1"));
        long a = HashUtil.pkHash(java.util.List.of(1L));
        long b = HashUtil.pkHash(java.util.List.of(new java.math.BigDecimal("1.00")));
        long c = HashUtil.pkHash(java.util.List.of(1));
        assertThat(a).isEqualTo(b).isEqualTo(c);
        assertThat(HashUtil.md5Hex("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo("900150983cd24fb0d6963f7d28e17f72");
    }

    @Test
    @DisplayName("雪花 ID：1 万次无重复且趋势递增")
    void should_generateUniqueIncreasing_When_10kIds() {
        SnowflakeId generator = new SnowflakeId(1);
        long previous = -1L;
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            long id = generator.nextId();
            assertThat(ids.add(id)).isTrue();
            assertThat(id).isGreaterThan(previous);
            previous = id;
        }
        SnowflakeId other = new SnowflakeId(2);
        assertThat(other.nextId()).isNotEqualTo(generator.nextId());
    }

    @Test
    @DisplayName("JSON 序列化与反序列化往返")
    void should_roundTrip_When_jsonSerialize() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", 1);
        map.put("name", "T_ORDER");
        map.put("nested", java.util.List.of(1, 2, 3));
        String json = JsonUtil.toJson(map);
        assertThat(json).contains("\"name\":\"T_ORDER\"");
        java.util.Map<String, Object> back = JsonUtil.parseObject(json);
        assertThat(back.get("name")).isEqualTo("T_ORDER");
        assertThat(JsonUtil.parseArray("[1,2,3]")).hasSize(3);
        assertThat(JsonUtil.parseObject("\"abc\"")).isEmpty();
        assertThat(JsonUtil.parseObject(null)).isEmpty();
    }
}
