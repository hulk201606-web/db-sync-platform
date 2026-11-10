package com.dbsync.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.api.ApiTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SEC-05 验收：会话空闲超时（默认 30 分钟）。
 *
 * <p>用可注入的 {@link Clock} 驱动时间，<b>不 sleep</b>：
 * "空闲 29 分钟仍有效 / 31 分钟失效"这类断言如果靠真实时间，测试要么慢 31 分钟，
 * 要么只能测个大概；可控时钟才能把边界钉死在分钟级。
 *
 * <p>与 TTL 的关系也在本类里钉死：两条规则<b>独立判定、先到者生效</b>，
 * 不能因为空闲未超时就放过一个 TTL 已到点的令牌（反之亦然）。
 */
class SessionIdleTimeoutTest extends ApiTestSupport {

    private static final String USERNAME = "idle-user";

    private MutableClock clock;

    /** 可控时钟（毫秒粒度，从固定时刻起步）。 */
    static final class MutableClock extends Clock {

        private final AtomicLong millis = new AtomicLong(1_700_000_000_000L);
        private final ZoneId zone = ZoneId.of("UTC");

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId ignored) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }

        /**
         * 前进指定时长。
         *
         * @param duration 时长
         */
        void advance(Duration duration) {
            millis.addAndGet(duration.toMillis());
        }
    }

    @BeforeEach
    void seedUser() {
        clock = new MutableClock();
        String salt = AuthService.newSaltHex();
        userDao.insert(USERNAME, USERNAME, AuthService.hashPassword(PASSWORD, salt), salt,
                Role.VIEWER.name());
    }

    @Test
    @DisplayName("① 空闲 29 分钟仍有效")
    void should_stayValid_When_idleBelowThreshold() {
        AuthService service = service(Duration.ofHours(8L), Duration.ofMinutes(30L));
        String token = login(service);

        clock.advance(Duration.ofMinutes(29L));

        assertTrue(service.authenticate(token).isPresent(), "29 分钟未超过 30 分钟阈值");
    }

    @Test
    @DisplayName("② 空闲 31 分钟失效且令牌被移除")
    void should_invalidateAndEvict_When_idleExceedsThreshold() {
        AuthService service = service(Duration.ofHours(8L), Duration.ofMinutes(30L));
        String token = login(service);

        clock.advance(Duration.ofMinutes(31L));

        assertTrue(service.authenticate(token).isEmpty(), "31 分钟应判定空闲超时");
        assertEquals(0, service.onlineCount(), "失效的令牌必须被移除，不能留在登记表里");
        assertTrue(service.authenticate(token).isEmpty(), "移除后再次校验也必须失败");
    }

    @Test
    @DisplayName("③ 每次访问刷新计时：第 20 分钟访问一次，第 45 分钟仍有效")
    void should_slideWindow_When_accessed() {
        AuthService service = service(Duration.ofHours(8L), Duration.ofMinutes(30L));
        String token = login(service);

        clock.advance(Duration.ofMinutes(20L));
        assertTrue(service.authenticate(token).isPresent(), "第 20 分钟访问应刷新计时");

        clock.advance(Duration.ofMinutes(25L)); // 距上次访问 25 分钟
        assertTrue(service.authenticate(token).isPresent(), "距上次访问 25 分钟仍在窗口内");

        clock.advance(Duration.ofMinutes(5L)); // 距上次访问 30 分钟（阈值，不算超）
        assertTrue(service.authenticate(token).isPresent(), "正好 30 分钟不算超过阈值");

        clock.advance(Duration.ofMinutes(31L)); // 距上次访问 31 分钟
        assertTrue(service.authenticate(token).isEmpty(), "31 分钟未访问应失效");
    }

    @Test
    @DisplayName("④ 配置 0 表示禁用空闲失效（只保留 TTL）")
    void should_neverIdleExpire_When_disabled() {
        AuthService service = service(Duration.ofHours(8L), Duration.ZERO);
        String token = login(service);

        clock.advance(Duration.ofHours(7L).plusMinutes(59L));

        assertTrue(service.authenticate(token).isPresent(), "禁用空闲超时后只受 TTL 约束");
        assertFalse(service.isIdleTimeoutEnabled());
    }

    @Test
    @DisplayName("⑤ TTL 到期优先失效（即使一直在访问）")
    void should_expireByTtl_When_ttlReached() {
        AuthService service = service(Duration.ofHours(1L), Duration.ofMinutes(30L));
        String token = login(service);

        // 每 10 分钟访问一次：空闲从未超时，但累计 61 分钟超过 TTL
        for (int i = 0; i < 6; i++) {
            clock.advance(Duration.ofMinutes(10L));
            assertTrue(service.authenticate(token).isPresent(), "第 " + (i + 1) * 10
                    + " 分钟：空闲未超时，TTL 也未到");
        }
        clock.advance(Duration.ofMinutes(1L));

        assertTrue(service.authenticate(token).isEmpty(), "累计 61 分钟超过 1h TTL，必须失效");
        assertEquals(0, service.onlineCount());
    }

    /**
     * 用指定 TTL / 空闲超时构造认证服务（共用本测试的可控时钟）。
     *
     * @param ttl         令牌有效期
     * @param idleTimeout 空闲超时
     * @return 认证服务
     */
    private AuthService service(Duration ttl, Duration idleTimeout) {
        return new AuthService(userDao, ttl, idleTimeout, clock);
    }

    /**
     * 登录并返回令牌。
     *
     * @param service 认证服务
     * @return 令牌
     */
    private String login(AuthService service) {
        Optional<CurrentUser> user = service.login(USERNAME, PASSWORD);
        return user.orElseThrow(() -> new IllegalStateException("登录失败")).getToken();
    }
}
