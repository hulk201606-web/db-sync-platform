package com.dbsync.api.security;

import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.metadata.dao.UserDao;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 认证服务：PBKDF2 口令校验 + 内存令牌。
 *
 * <p><b>口令算法</b>：{@code PBKDF2WithHmacSHA256}，迭代 120000 次，输出 256 bit，
 * 盐为 16 字节随机数的十六进制串（与 {@code V2__seed.sql} 的种子管理员一致，
 * 口令明文见 README：{@code Admin@12345}，首登强制改密）。
 *
 * <p><b>令牌为什么放内存</b>：本期是单实例/多实例无共享存储的最小实现，
 * 令牌只用于把"HTTP 请求 → 用户名/角色"绑定起来；把令牌落库会引入"令牌表"，
 * 与 04 文档的 17 张表清单冲突。多实例场景下用负载均衡的会话保持即可
 * （架构 §2.2 已声明本期不做分布式会话）。{@code // ADDED: 令牌存储位置 04 文档未规定}。
 */
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 迭代次数（与种子数据一致，改动会导致 admin 无法登录）。 */
    public static final int ITERATIONS = 120000;
    /** 密钥长度（bit）。 */
    public static final int KEY_LENGTH = 256;
    /** 盐长度（字节）。 */
    private static final int SALT_BYTES = 16;
    /** 默认令牌有效期。 */
    public static final Duration DEFAULT_TTL = Duration.ofHours(8L);

    /** 默认会话空闲超时（PRD SEC-05：30 分钟不动即失效）。 */
    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(30L);

    private final UserDao userDao;
    private final Duration ttl;
    /** 空闲超时；{@code ZERO} 表示禁用空闲失效（只保留 TTL）。 */
    private final Duration idleTimeout;
    /** 时钟（可注入：单测用可控时钟驱动"空闲 31 分钟"，不 sleep 真实时间）。 */
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, CurrentUser> tokens = new ConcurrentHashMap<>();
    private final AtomicInteger issued = new AtomicInteger();

    /**
     * 构造（默认 TTL 8h + 默认空闲 30min + 系统时钟）。
     *
     * @param userDao 用户 DAO
     */
    public AuthService(UserDao userDao) {
        this(userDao, DEFAULT_TTL, DEFAULT_IDLE_TIMEOUT, Clock.systemUTC());
    }

    /**
     * 构造（自定义令牌有效期，便于单测）。
     *
     * @param userDao 用户 DAO
     * @param ttl     令牌有效期
     */
    public AuthService(UserDao userDao, Duration ttl) {
        this(userDao, ttl, DEFAULT_IDLE_TIMEOUT, Clock.systemUTC());
    }

    /**
     * 构造（完整参数）。
     *
     * @param userDao     用户 DAO
     * @param ttl         令牌有效期（签发后最长存活时间；≤0 走默认 8h）
     * @param idleTimeout 空闲超时（{@code ZERO}/负数表示禁用空闲失效）
     * @param clock       时钟（null 走系统时钟）
     */
    public AuthService(UserDao userDao, Duration ttl, Duration idleTimeout, Clock clock) {
        this.userDao = userDao;
        this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
        this.idleTimeout = idleTimeout == null || idleTimeout.isNegative() ? Duration.ZERO
                : idleTimeout;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * 登录。
     *
     * @param username 用户名
     * @param password 明文口令
     * @return 登录成功返回令牌持有者；用户名或口令错误返回 {@code Optional.empty()}
     */
    public Optional<CurrentUser> login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            return Optional.empty();
        }
        Optional<UserDao.UserRow> found = userDao.findByUsername(username.trim());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        UserDao.UserRow row = found.get();
        if (!row.isEnabled()) {
            log.warn("登录被拒：用户已禁用 username={}", row.getUsername());
            return Optional.empty();
        }
        if (!verifyPassword(password, row.getSalt(), row.getPasswordHash())) {
            return Optional.empty();
        }
        String token = newToken();
        Instant now = clock.instant();
        CurrentUser user = new CurrentUser(row.getUsername(), row.getDisplayName(),
                Role.parse(row.getRole()), token, now.plus(ttl), now);
        tokens.put(token, user);
        userDao.updateLastLogin(row.getUsername());
        issued.incrementAndGet();
        log.info("登录成功 username={} role={} 在线令牌数={}", row.getUsername(), user.getRole(),
                tokens.size());
        return Optional.of(user);
    }

    /**
     * 校验令牌（TTL + 空闲超时两条规则）。
     *
     * <p><b>两条规则的关系</b>（PRD SEC-05）：{@code expiresAt} 是"签发后最长活多久"，
     * {@code idleTimeout} 是"多久不动就失效"，两者<b>独立判定、先到者生效</b>：
     * <ul>
     *   <li>TTL 到点 → 失效（与是否活跃无关）；</li>
     *   <li>距 {@code lastAccessAt} 超过空闲阈值 → 失效，并<b>立即移除</b>该令牌；</li>
     *   <li>都未触发 → 刷新 {@code lastAccessAt}（这是"滑动窗口"的来源）。</li>
     * </ul>
     *
     * <p>失效一律返回 {@code Optional.empty()}：调用方（{@code RbacInterceptor}）已有
     * "401 + {@code E8004}" 的统一语义，不为"空闲失效"新增错误码——
     * 对客户端来说"要重新登录"是同一件事，区分原因只会让前端多写一个分支。
     *
     * @param token 令牌
     * @return 有效时返回用户；无效/过期/空闲超时返回 {@code Optional.empty()}
     */
    public Optional<CurrentUser> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String key = token.trim();
        CurrentUser user = tokens.get(key);
        if (user == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (user.getExpiresAt() != null && now.isAfter(user.getExpiresAt())) {
            tokens.remove(key);
            log.info("令牌超过 TTL，已失效 username={}", user.getUsername());
            return Optional.empty();
        }
        if (isIdleTimeoutEnabled()) {
            Instant last = user.getLastAccessAt();
            if (last != null && Duration.between(last, now).compareTo(idleTimeout) > 0) {
                tokens.remove(key);
                log.info("会话空闲超时（{} 分钟），令牌已失效 username={}", Long.valueOf(
                        idleTimeout.toMinutes()), user.getUsername());
                return Optional.empty();
            }
        }
        user.touch(now);
        return Optional.of(user);
    }

    /**
     * @return 是否启用空闲失效（{@code idleTimeoutMinutes=0} 时为 false）
     */
    public boolean isIdleTimeoutEnabled() {
        return !idleTimeout.isZero() && !idleTimeout.isNegative();
    }

    /**
     * @return 空闲超时配置（{@code ZERO} 表示禁用）
     */
    public Duration idleTimeout() {
        return idleTimeout;
    }

    /**
     * 登出。
     *
     * @param token 令牌
     */
    public void logout(String token) {
        if (token != null) {
            tokens.remove(token.trim());
        }
    }

    /**
     * 修改口令（校验旧口令后换盐重算）。
     *
     * @param username    用户名
     * @param oldPassword 旧口令
     * @param newPassword 新口令
     * @return 修改成功返回 true；旧口令错误返回 false
     */
    public boolean changePassword(String username, String oldPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "新口令长度需 ≥8");
        }
        Optional<UserDao.UserRow> found = userDao.findByUsername(username);
        if (found.isEmpty()) {
            return false;
        }
        UserDao.UserRow row = found.get();
        if (!verifyPassword(oldPassword, row.getSalt(), row.getPasswordHash())) {
            return false;
        }
        String salt = newSaltHex();
        userDao.updatePassword(username, hashPassword(newPassword, salt), salt);
        // 改密后踢掉该用户全部令牌（安全要求：会话失效）
        tokens.entrySet().removeIf(e -> e.getValue().getUsername().equals(username));
        return true;
    }

    /**
     * 后台清理过期令牌（TTL 到点 / 空闲超时），避免只靠"访问时惰性失效"导致的内存堆积。
     *
     * <p>线程安全：遍历 {@link ConcurrentHashMap} 的弱一致视图，用
     * {@code remove(key, value)} 条件删除，与 {@link #authenticate} 的惰性失效、
     * {@link #login} 的写入可以并发，不会误删刚签发/刚续期的令牌。
     * 判据与 {@link #authenticate} 完全一致（TTL + 空闲超时，先到者生效）。
     *
     * @return 本次清理的令牌数
     */
    public int purgeExpired() {
        Instant now = clock.instant();
        int removed = 0;
        for (Map.Entry<String, CurrentUser> entry : tokens.entrySet()) {
            CurrentUser user = entry.getValue();
            if (isExpired(user, now) && tokens.remove(entry.getKey(), user)) {
                removed++;
            }
        }
        return removed;
    }

    /**
     * 令牌是否已过期（与 {@link #authenticate} 同判据）。
     *
     * @param user 用户
     * @param now  当前时间
     * @return true 表示过期
     */
    private boolean isExpired(CurrentUser user, Instant now) {
        if (user.getExpiresAt() != null && now.isAfter(user.getExpiresAt())) {
            return true;
        }
        return isIdleTimeoutEnabled() && user.getLastAccessAt() != null
                && Duration.between(user.getLastAccessAt(), now).compareTo(idleTimeout) > 0;
    }

    /**
     * 在线令牌数（大盘/测试用）。
     *
     * @return 数量
     */
    public int onlineCount() {
        return tokens.size();
    }

    /**
     * 累计签发数。
     *
     * @return 数量
     */
    public int issuedCount() {
        return issued.get();
    }

    /**
     * PBKDF2 计算口令哈希。
     *
     * @param password 明文口令
     * @param saltHex  盐（十六进制）
     * @return 哈希（十六进制，64 位小写）
     */
    public static String hashPassword(String password, String saltHex) {
        if (password == null) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "口令不能为空");
        }
        if (saltHex == null || saltHex.isBlank()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "盐不能为空");
        }
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), hexToBytes(saltHex), ITERATIONS,
                KEY_LENGTH);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return bytesToHex(factory.generateSecret(spec).getEncoded());
        } catch (Exception e) {
            throw new DbSyncException(ErrorCode.CRYPTO_FAILED, e, "PBKDF2 计算失败");
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * 常量时间比对口令（避免时序侧信道）。
     *
     * @param password 明文口令
     * @param saltHex  盐
     * @param expected 期望哈希
     * @return 一致返回 true
     */
    public static boolean verifyPassword(String password, String saltHex, String expected) {
        if (password == null || saltHex == null || expected == null) {
            return false;
        }
        String actual = hashPassword(password, saltHex);
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @return 新的 16 字节随机盐（十六进制）
     */
    public static String newSaltHex() {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        return bytesToHex(salt);
    }

    private String newToken() {
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        String normalized = hex.trim();
        if (normalized.length() % 2 != 0) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "盐长度非法");
        }
        byte[] out = new byte[normalized.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(normalized.charAt(i * 2), 16);
            int lo = Character.digit(normalized.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new DbSyncException(ErrorCode.PARAM_INVALID, "盐包含非法字符");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
