package com.dbsync.api.security;

import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import java.time.Instant;

/**
 * 当前登录用户（请求级上下文）。
 *
 * <p>为什么用 {@link ThreadLocal} 而不是把 {@code CurrentUser} 一路透传到 Service 层：
 * 审计与 RBAC 都在 Controller 之前的拦截器里，业务方法签名不应为了"记录是谁干的"而改变
 * （否则每个 Service 方法都要多一个 operator 参数，跨模块调用会污染 Port 设计）。
 * 生命周期由 {@code RbacInterceptor} 的 {@code afterCompletion} 严格清理。
 *
 * <p><b>不可变性</b>：实例字段除 {@link #lastAccessAt} 外全部 final；{@code token}/{@code expiresAt}
 * 由 {@link AuthService} 在签发时确定，请求处理过程中不改。
 *
 * <p><b>线程安全</b>（SEC-05 引入 {@code lastAccessAt} 后的必读说明）：
 * 令牌登记表（{@code AuthService.tokens}）是 {@code ConcurrentHashMap}，同一个
 * {@code CurrentUser} 实例会被多个请求线程同时读到——因此"最后一次访问时间"必须是
 * {@code volatile} 字段（单写多读，只关心可见性，不需要原子自增语义）。
 * 不用"重建实例再入池"的写法：那会在每次请求都新建对象并做一次 CAS 替换，
 * 高并发下白白制造垃圾与竞争，而这里需要的只是一个时间戳的可见性。
 */
public final class CurrentUser {

    /** 请求头名。 */
    public static final String TOKEN_HEADER = "X-DBSync-Token";

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private final String username;
    private final String displayName;
    private final Role role;
    private final String token;
    private final Instant expiresAt;

    /**
     * 最后一次访问时间（SEC-05 空闲超时的计时基准）。
     *
     * <p>{@code volatile}：见类注释的线程安全说明；null 表示"尚未记录"，
     * {@link AuthService#authenticate} 会把首次访问当作基准时间。
     */
    private volatile Instant lastAccessAt;

    /**
     * 构造（不记录访问时间：首次 {@code authenticate} 时补上）。
     *
     * @param username    用户名
     * @param displayName 显示名
     * @param role        角色
     * @param token       令牌
     * @param expiresAt   过期时间
     */
    public CurrentUser(String username, String displayName, Role role, String token,
                       Instant expiresAt) {
        this(username, displayName, role, token, expiresAt, null);
    }

    /**
     * 构造。
     *
     * @param username     用户名
     * @param displayName  显示名
     * @param role         角色
     * @param token        令牌
     * @param expiresAt    过期时间（TTL 到点）
     * @param lastAccessAt 最后一次访问时间（可 null）
     */
    public CurrentUser(String username, String displayName, Role role, String token,
                       Instant expiresAt, Instant lastAccessAt) {
        this.username = username;
        this.displayName = displayName == null ? username : displayName;
        this.role = role == null ? Role.VIEWER : role;
        this.token = token;
        this.expiresAt = expiresAt;
        this.lastAccessAt = lastAccessAt;
    }

    /**
     * 绑定到当前线程。
     *
     * @param user 用户（null 时等于清理）
     */
    public static void set(CurrentUser user) {
        if (user == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(user);
        }
    }

    /**
     * @return 当前用户；未登录返回 null
     */
    public static CurrentUser get() {
        return HOLDER.get();
    }

    /**
     * 清理当前线程绑定（拦截器 afterCompletion 必调，避免线程池串味）。
     */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 取当前用户，未登录抛 401。
     *
     * @return 当前用户
     */
    public static CurrentUser require() {
        CurrentUser user = HOLDER.get();
        if (user == null) {
            throw new DbSyncException(ErrorCode.TOKEN_INVALID, "缺少或无效的登录令牌");
        }
        return user;
    }

    /**
     * 取当前用户名，未登录返回 {@code "anonymous"}（审计兜底，不抛异常）。
     *
     * @return 用户名
     */
    public static String actorOrAnonymous() {
        CurrentUser user = HOLDER.get();
        return user == null ? "anonymous" : user.getUsername();
    }

    /**
     * @return 用户名
     */
    public String getUsername() {
        return username;
    }

    /**
     * @return 显示名
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * @return 角色
     */
    public Role getRole() {
        return role;
    }

    /**
     * @return 令牌
     */
    public String getToken() {
        return token;
    }

    /**
     * @return 过期时间
     */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /**
     * @return 最后一次访问时间；null 表示尚未记录
     */
    public Instant getLastAccessAt() {
        return lastAccessAt;
    }

    /**
     * 刷新最后一次访问时间（SEC-05：每次成功鉴权后调用）。
     *
     * <p>只写一个 volatile 引用：多请求线程并发刷新时"最后写入者胜出"，
     * 这对空闲超时完全够用（我们关心的是"最近有没有人用过"，而不是精确的访问序列）。
     *
     * @param at 访问时间（null 忽略）
     */
    public void touch(Instant at) {
        if (at != null) {
            this.lastAccessAt = at;
        }
    }

    /**
     * 判定权限。
     *
     * @param permission 权限点
     * @return 有权限返回 true
     */
    public boolean can(Permission permission) {
        return role.has(permission);
    }

    /**
     * @return 是否管理员
     */
    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    @Override
    public String toString() {
        return "CurrentUser[" + username + "/" + role + "]";
    }
}
