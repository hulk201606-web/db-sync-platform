package com.dbsync.common.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台统一业务异常。
 *
 * <p>约定：
 * <ul>
 *   <li>所有对外可识别的失败都用本类（或其子类）表达，携带 {@link ErrorCode}；</li>
 *   <li>{@code context} 是结构化上下文，最终会进入 HTTP 响应体的 {@code data} 字段；</li>
 *   <li>{@link #with(String, Object)} 链式追加上下文，返回 {@code this}（便于一行内组装）。</li>
 * </ul>
 */
public class DbSyncException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final Map<String, Object> context = new LinkedHashMap<>();

    /**
     * 构造。
     *
     * @param errorCode 错误码，不可空
     * @param cause     根因，可为 null
     * @param args      消息模板参数
     */
    public DbSyncException(ErrorCode errorCode, Throwable cause, Object... args) {
        super(errorCode == null ? "unknown" : errorCode.format(args), cause);
        this.errorCode = errorCode == null ? ErrorCode.INTERNAL_ERROR : errorCode;
    }

    /**
     * 构造（无根因）。
     *
     * @param errorCode 错误码
     * @param args      消息模板参数
     */
    public DbSyncException(ErrorCode errorCode, Object... args) {
        this(errorCode, null, args);
    }

    /**
     * @return 错误码
     */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * @return 不可变的结构化上下文
     */
    public Map<String, Object> context() {
        return Collections.unmodifiableMap(context);
    }

    /**
     * 追加结构化上下文。
     *
     * @param key   键；null 时忽略
     * @param value 值
     * @return this（链式）
     */
    public DbSyncException with(String key, Object value) {
        if (key != null) {
            context.put(key, value);
        }
        return this;
    }

    /**
     * @return 该错误是否可重试（决定 RetryTemplate 行为）
     */
    public boolean isRetryable() {
        return errorCode.retryable();
    }
}
