package com.dbsync.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 结构化日志（架构 §1.4 / §11 日志规范）。
 *
 * <p>强制字段：{@code traceId}、{@code taskId}、{@code shardId|groupNo}、{@code table}、{@code phase}。
 * 用法：
 * <pre>
 * StructuredLog.info("batch committed")
 *     .kv("taskId", ctx.taskId())
 *     .kv("shardId", shard.getId())
 *     .kv("table", table.getTableName())
 *     .kv("phase", "MIGRATION")
 *     .kv("rows", result.getRowsAffected())
 *     .log();
 * </pre>
 *
 * <p><b>禁止</b>：打印密码、完整 JDBC URL、before/after 全行内容。
 */
public final class StructuredLog {

    private final Logger logger;
    private final StringBuilder message;
    private final boolean warn;
    private final boolean error;
    private final Throwable cause;

    private StructuredLog(Logger logger, String msg, boolean warn, boolean error, Throwable cause) {
        this.logger = logger;
        this.message = new StringBuilder(msg == null ? "" : msg);
        this.warn = warn;
        this.error = error;
        this.cause = cause;
    }

    /**
     * INFO 级别。
     *
     * @param msg 消息
     * @return builder
     */
    public static StructuredLog info(String msg) {
        return new StructuredLog(LoggerFactory.getLogger(resolveCaller()), msg, false, false, null);
    }

    /**
     * WARN 级别。
     *
     * @param msg 消息
     * @return builder
     */
    public static StructuredLog warn(String msg) {
        return new StructuredLog(LoggerFactory.getLogger(resolveCaller()), msg, true, false, null);
    }

    /**
     * ERROR 级别。
     *
     * @param msg   消息
     * @param cause 异常（可 null）
     * @return builder
     */
    public static StructuredLog error(String msg, Throwable cause) {
        return new StructuredLog(LoggerFactory.getLogger(resolveCaller()), msg, false, true, cause);
    }

    /**
     * 以指定 logger 名创建（引擎类用它固定 logger，便于日志过滤）。
     *
     * @param loggerName logger 名
     * @param msg        消息
     * @return builder
     */
    public static StructuredLog of(String loggerName, String msg) {
        return new StructuredLog(LoggerFactory.getLogger(loggerName), msg, false, false, null);
    }

    /**
     * 追加键值对。
     *
     * @param key   键
     * @param value 值（null 输出为 {@code -}）
     * @return this
     */
    public StructuredLog kv(String key, Object value) {
        message.append(' ').append(key).append('=').append(value == null ? "-" : value);
        return this;
    }

    /**
     * 输出日志。
     */
    public void log() {
        if (error) {
            logger.error(message.toString(), cause);
        } else if (warn) {
            logger.warn(message.toString());
        } else {
            logger.info(message.toString());
        }
    }

    /**
     * 取调用方类名作为 logger 名（让日志归属到引擎类而不是工具类）。
     *
     * @return logger 名
     */
    private static String resolveCaller() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement e : stack) {
            String cn = e.getClassName();
            if (!cn.equals(StructuredLog.class.getName())
                    && !cn.equals("java.lang.Thread")) {
                return cn;
            }
        }
        return StructuredLog.class.getName();
    }
}
