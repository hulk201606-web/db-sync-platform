package com.dbsync.server.config;

import com.dbsync.api.audit.AuditAspect;
import com.dbsync.api.audit.AuditService;
import com.dbsync.api.security.AuthService;
import com.dbsync.api.security.RbacInterceptor;
import com.dbsync.api.service.UserService;
import com.dbsync.core.port.AuditPort;
import com.dbsync.metadata.dao.AuditLogDao;
import com.dbsync.metadata.dao.UserDao;
import java.time.Clock;
import java.util.function.Predicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 认证 / 授权 / 审计装配（T42、T46）。
 *
 * <p>本平台<b>不用 Spring Security</b>（依赖白名单里没有）：RBAC 由两个
 * {@code HandlerInterceptor} 完成——先 RBAC（鉴权）后审计（留痕），
 * 顺序即"拒绝也要留痕"的保证。
 */
@Configuration
public class SecurityConfig {

    /**
     * 认证服务（PBKDF2 + 令牌 + 空闲超时）。
     *
     * @param userDao    用户 DAO
     * @param properties 配置（令牌 TTL / 空闲超时）
     * @param clock      时钟（与 {@code MetricsConfig#clock} 同一个 bean，便于整体替换）
     * @return 服务
     */
    @Bean
    public AuthService authService(UserDao userDao, DbSyncProperties properties, Clock clock) {
        return new AuthService(userDao, properties.tokenTtl(), properties.tokenIdleTimeout(),
                clock);
    }

    /**
     * 审计服务（同时是 {@link AuditPort} 的唯一实现）。
     *
     * @param auditLogDao 审计 DAO
     * @return 服务
     */
    @Bean
    public AuditService auditService(AuditLogDao auditLogDao) {
        return new AuditService(auditLogDao);
    }

    /**
     * 用户服务（ADMIN 管理账号）。
     *
     * @param userDao 用户 DAO
     * @return 服务
     */
    @Bean
    public UserService userService(UserDao userDao) {
        return new UserService(userDao);
    }

    /**
     * RBAC 拦截器（鉴权 + 拒绝留痕）。
     *
     * @param authService 认证服务
     * @param auditPort   审计端口
     * @return 拦截器
     */
    @Bean
    public RbacInterceptor rbacInterceptor(AuthService authService, AuditPort auditPort) {
        return new RbacInterceptor(authService, auditPort);
    }

    /**
     * 审计拦截器（{@code @Audited} → 写审计）。
     *
     * <p>// ADDED: 白名单没有 {@code spring-boot-starter-aop}，因此 {@code AuditAspect}
     * 用 {@code HandlerInterceptor} 实现而不是 {@code @Aspect}；注解检测逻辑一致。
     *
     * @param auditPort 审计端口
     * @return 拦截器
     */
    @Bean
    public AuditAspect auditAspect(AuditPort auditPort) {
        return new AuditAspect(auditPort);
    }

    /**
     * "操作人是否 ADMIN"判定器（供反修等高危动作二次校验）。
     *
     * <p>以库中角色为准，而不是只在请求线程里信任令牌声明的角色——
     * 账号被降权后，旧令牌不能继续做高危操作。
     *
     * @param userDao 用户 DAO
     * @return 判定器
     */
    @Bean
    public Predicate<String> adminChecker(UserDao userDao) {
        return operator -> operator != null && userDao.findByUsername(operator)
                .map(row -> "ADMIN".equalsIgnoreCase(row.getRole()))
                .orElse(false);
    }
}
