package com.dbsync.common.util;

import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 指数退避重试（架构 §4.1.3 V6：批次失败整批重试，不做部分补偿）。
 *
 * <p>退避序列：{@code initialBackoff × multiplier^(n-1)}，每次乘 {@code (1 ± jitter)}。
 * 例如默认参数下为 1s / 2s / 4s ±20%。
 *
 * <p>{@link InterruptedException} <b>不重试</b>，直接抛出并恢复中断标记
 * （重试信号量类异常会把优雅停机拖成 30 秒）。
 */
public final class RetryTemplate {

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final double multiplier;
    private final double jitter;

    /**
     * 构造。
     *
     * @param maxAttempts    最大尝试次数（含首次），≥1
     * @param initialBackoff 首次退避
     * @param multiplier     退避倍数（≥1）
     * @param jitter         抖动比例 0..1
     */
    public RetryTemplate(int maxAttempts, Duration initialBackoff, double multiplier, double jitter) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoff = initialBackoff == null ? Duration.ofSeconds(1) : initialBackoff;
        this.multiplier = multiplier < 1.0d ? 1.0d : multiplier;
        this.jitter = Math.max(0.0d, Math.min(1.0d, jitter));
    }

    /**
     * @return 默认重试模板（3 次，1s 起，×2，±20%）
     */
    public static RetryTemplate defaults() {
        return new RetryTemplate(3, Duration.ofSeconds(1), 2.0d, 0.2d);
    }

    /**
     * 执行带重试的动作。
     *
     * @param action    动作
     * @param retryable 判定异常是否可重试；返回 false 表示立即抛出
     * @param <T>       返回类型
     * @return 动作结果
     * @throws DbSyncException {@code E1020} 重试耗尽（携带最后一次的根因）
     */
    public <T> T execute(Supplier<T> action, Predicate<Throwable> retryable) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (e.getCause() instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                if (retryable != null && !retryable.test(e)) {
                    throw e;
                }
                last = e;
                if (attempt == maxAttempts) {
                    break;
                }
                sleep(backoffOf(attempt));
            }
        }
        throw new DbSyncException(ErrorCode.RETRY_EXHAUSTED, last,
                "attempts=" + maxAttempts);
    }

    /**
     * 第 n 次失败后的退避时长。
     *
     * @param attempt 已失败次数（从 1 开始）
     * @return 退避时长
     */
    Duration backoffOf(int attempt) {
        double base = initialBackoff.toMillis() * Math.pow(multiplier, Math.max(0, attempt - 1));
        double factor = 1.0d + (jitter == 0.0d ? 0.0d
                : ThreadLocalRandom.current().nextDouble(-jitter, jitter));
        return Duration.ofMillis((long) Math.max(1L, base * factor));
    }

    /**
     * 可中断休眠。
     *
     * @param d 时长
     */
    private void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new DbSyncException(ErrorCode.RETRY_EXHAUSTED, ie, "重试等待被中断");
        }
    }

    /**
     * @return 最大尝试次数
     */
    public int maxAttempts() {
        return maxAttempts;
    }
}
