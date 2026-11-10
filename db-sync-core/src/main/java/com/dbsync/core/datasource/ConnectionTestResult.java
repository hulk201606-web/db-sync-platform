package com.dbsync.core.datasource;

import java.time.Instant;

/**
 * 连接探测结果。
 *
 * <p>归类目的是让运维一眼看出"该找 DBA 还是该找网络组"：
 * <ul>
 *   <li>{@code AUTH}：账号密码错 → 改配置；</li>
 *   <li>{@code NETWORK}：网络不通/端口错 → 找网络组；</li>
 *   <li>{@code DRIVER}：驱动没放到位 → 看 {@code lib/README.md}；</li>
 *   <li>{@code PRIVILEGE}：能连上但没权限（例如缺 SELECT 或 schema 不存在）→ 找 DBA 授权；</li>
 *   <li>{@code UNKNOWN}：未归类的异常，必须原样展示堆栈首行，禁止猜。</li>
 * </ul>
 */
public final class ConnectionTestResult {

    /** 失败归类。 */
    public enum Category {
        /** 认证失败（用户名/口令错误、账号被锁）。 */
        AUTH,
        /** 网络不可达（拒绝连接、超时、DNS 失败）。 */
        NETWORK,
        /** 驱动缺失或版本不匹配。 */
        DRIVER,
        /** 已连通但权限/schema 不足。 */
        PRIVILEGE,
        /** ADDED：未归类异常（不猜，避免把未知错误误导成网络问题）。 */
        UNKNOWN
    }

    private final boolean success;
    private final Category category;
    private final long latencyMs;
    private final String serverVersion;
    private final String errorCode;
    private final String message;
    private final String hint;
    private final Instant testedAt;

    /**
     * 构造。
     *
     * @param success       是否成功
     * @param category      失败归类（成功时为 null）
     * @param latencyMs     往返耗时
     * @param serverVersion 服务端版本（成功时）
     * @param errorCode     错误码/错误 SQLState（失败时）
     * @param message       原始错误消息（截断）
     * @param hint          处置建议
     * @param testedAt      探测时间
     */
    public ConnectionTestResult(boolean success, Category category, long latencyMs,
                                String serverVersion, String errorCode, String message,
                                String hint, Instant testedAt) {
        this.success = success;
        this.category = category;
        this.latencyMs = latencyMs;
        this.serverVersion = serverVersion;
        this.errorCode = errorCode;
        this.message = message;
        this.hint = hint;
        this.testedAt = testedAt == null ? Instant.now() : testedAt;
    }

    /**
     * 构造成功结果。
     *
     * @param latencyMs     耗时
     * @param serverVersion 版本
     * @return 结果
     */
    public static ConnectionTestResult ok(long latencyMs, String serverVersion) {
        return new ConnectionTestResult(true, null, latencyMs, serverVersion, null, null, null,
                Instant.now());
    }

    /**
     * 构造失败结果。
     *
     * @param category  归类
     * @param errorCode 错误码
     * @param message   原始消息
     * @param hint      处置建议
     * @param latencyMs 耗时
     * @return 结果
     */
    public static ConnectionTestResult fail(Category category, String errorCode, String message,
                                           String hint, long latencyMs) {
        return new ConnectionTestResult(false, category, latencyMs, null, errorCode,
                truncate(message, 1000), hint, Instant.now());
    }

    /** @return 是否成功 */
    public boolean isSuccess() {
        return success;
    }

    /** @return 失败归类（成功时 null） */
    public Category getCategory() {
        return category;
    }

    /** @return 往返耗时 */
    public long getLatencyMs() {
        return latencyMs;
    }

    /** @return 服务端版本 */
    public String getServerVersion() {
        return serverVersion;
    }

    /** @return 错误码 */
    public String getErrorCode() {
        return errorCode;
    }

    /** @return 原始消息 */
    public String getMessage() {
        return message;
    }

    /** @return 处置建议 */
    public String getHint() {
        return hint;
    }

    /** @return 探测时间 */
    public Instant getTestedAt() {
        return testedAt;
    }

    /**
     * 截断字符串。
     *
     * @param value 原值
     * @param max   上限
     * @return 截断值
     */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    @Override
    public String toString() {
        return success ? "ConnectionTestResult[OK," + latencyMs + "ms," + serverVersion + "]"
                : "ConnectionTestResult[FAIL," + category + "," + errorCode + "]";
    }
}
