package com.dbsync.server.config;

import com.dbsync.api.security.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 过期令牌后台清理（SEC-05 配套）。
 *
 * <p>{@link AuthService} 原本只有"访问时惰性失效"：一个令牌若之后再没被访问，
 * 会一直留在内存 {@code ConcurrentHashMap} 里直到进程重启。这里周期性调用
 * {@link AuthService#purgeExpired()} 主动清理，避免长期运行的实例内存缓慢堆积。
 *
 * <p><b>不新建调度线程池</b>：直接复用 Spring 的调度线程池
 * （{@code spring.task.scheduling.pool.size}，当前为 6，与心跳 / 探活 / 告警评估等作业共用；
 * 新增调度入口时须同步调大该值，见 application.yml 注释），
 * 不引入额外的单线程调度器；清理本身是 O(n) 的轻量 Map 遍历，且异常已被吞掉并记录。
 */
@Component
public class AuthTokenCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenCleanupScheduler.class);

    private final AuthService authService;

    /**
     * 构造。
     *
     * @param authService 认证服务（{@code SecurityConfig#authService} 装配的单例）
     */
    public AuthTokenCleanupScheduler(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 周期清理过期令牌（默认 60s，可用 {@code dbsync.jobs.token-cleanup-ms} 覆盖）。
     */
    @Scheduled(initialDelayString = "${dbsync.jobs.token-cleanup-ms:60000}",
            fixedDelayString = "${dbsync.jobs.token-cleanup-ms:60000}")
    public void purgeExpiredTokens() {
        try {
            int removed = authService.purgeExpired();
            if (removed > 0) {
                log.info("过期令牌清理完成 removed={} 在线令牌数={}", Integer.valueOf(removed),
                        Integer.valueOf(authService.onlineCount()));
            }
        } catch (RuntimeException e) {
            log.warn("过期令牌清理异常（已忽略，下轮重试）", e);
        }
    }
}
