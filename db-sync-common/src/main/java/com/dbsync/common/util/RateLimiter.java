package com.dbsync.common.util;

import java.util.concurrent.locks.LockSupport;

/**
 * 行级令牌桶限速器（架构 §13.5 双层限速的"读"层）。
 *
 * <p>语义：{@link #tryAcquireRows(long)} 是一次"问问现在还能不能走这么多行"，
 * 返回 {@code [0, maxRows]}；返回 0 表示当前无许可。
 * {@link #acquireBlocking(int)} 会阻塞等待直到至少拿到 1 行许可（不空转）。
 *
 * <p>实现要点：桶容量按 1 秒补齐，允许 {@code 1.05} 倍突发（单测容差），
 * 但不会无限累积（{@code capacity = permitsPerSecond}）。
 */
public final class RateLimiter {

    private final long capacity;
    private double tokens;
    private long lastRefillNanos;
    private volatile long permitsPerSecond;
    private final Object lock = new Object();

    /**
     * 构造。
     *
     * @param permitsPerSecond 每秒许可行数（≤0 表示不限速，{@link #tryAcquireRows} 恒返回入参）
     */
    public RateLimiter(long permitsPerSecond) {
        this.permitsPerSecond = Math.max(0, permitsPerSecond);
        this.capacity = Math.max(1, permitsPerSecond);
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * 尝试获取许可（非阻塞）。
     *
     * @param maxRows 期望行数
     * @return 获批行数，0 表示当前无许可
     */
    public long tryAcquireRows(long maxRows) {
        if (maxRows <= 0) {
            return 0;
        }
        if (permitsPerSecond <= 0) {
            return maxRows;
        }
        synchronized (lock) {
            refill();
            long grant = (long) Math.min(maxRows, Math.floor(tokens));
            if (grant <= 0) {
                return 0;
            }
            tokens -= grant;
            return grant;
        }
    }

    /**
     * 阻塞获取许可（至少 1 行）。
     *
     * @param requested 期望行数
     * @return 实际获批行数（≥1）
     */
    public int acquireBlocking(int requested) {
        if (requested <= 0) {
            return 0;
        }
        if (permitsPerSecond <= 0) {
            return requested;
        }
        while (true) {
            long grant = tryAcquireRows(requested);
            if (grant > 0) {
                return (int) grant;
            }
            // 至少留 1 行许可再返回，避免频繁自旋
            LockSupport.parkNanos(200_000L);
        }
    }

    /**
     * 运行时调整速率，≤1s 生效。
     *
     * @param permitsPerSecond 新的每秒许可行数
     */
    public void updateRate(long permitsPerSecond) {
        synchronized (lock) {
            this.permitsPerSecond = Math.max(0, permitsPerSecond);
            this.tokens = Math.min(this.tokens, Math.max(1, permitsPerSecond));
            this.lastRefillNanos = System.nanoTime();
        }
    }

    /**
     * @return 当前速率
     */
    public long permitsPerSecond() {
        return permitsPerSecond;
    }

    /** 按经过时间补充令牌。 */
    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        if (elapsed <= 0) {
            return;
        }
        double added = (double) elapsed / 1_000_000_000.0d * permitsPerSecond;
        if (added > 0) {
            tokens = Math.min(capacity, tokens + added);
            lastRefillNanos = now;
        }
    }
}
