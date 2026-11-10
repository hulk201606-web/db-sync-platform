package com.dbsync.core.throttle;

import com.dbsync.common.util.RateLimiter;

/**
 * 双层限速器（架构 §13.5）：读限速与写限速分开控制。
 *
 * <p>为什么要分开：读慢只影响源库压力，写慢会影响目标库与主从延迟；
 * 迁移时通常"读得快、写得慢"（目标库还要服务线上读），
 * 合成一个令牌桶会让读也被写拖住，白白浪费窗口。
 *
 * <p>语义：{@link #acquireReadRows(int)} / {@link #acquireWriteRows(int)} <b>阻塞</b>直到
 * 至少拿到 1 行许可，并返回实际获批行数——调用方据此缩小本批大小，
 * 而不是"拿到 0 就空转"（空转会打满 CPU 且让限速看起来失效）。
 *
 * <p>{@code rowsPerSec <= 0} 表示不限速（直接放行，不做任何 park）。
 */
public class Throttle {

    /**
     * 读令牌桶（null 表示不限速）。
     *
     * <p>{@code volatile} + 非 final：{@link #updateLimits} 允许"从不停限速改为限速"时
     * 现场建桶（见该方法注释）。
     */
    private volatile RateLimiter readLimiter;

    /** 写令牌桶（null 表示不限速），同上。 */
    private volatile RateLimiter writeLimiter;

    /**
     * 构造。
     *
     * @param readRowsPerSec  读限速（行/秒，≤0 不限速）
     * @param writeRowsPerSec 写限速（行/秒，≤0 不限速）
     */
    public Throttle(long readRowsPerSec, long writeRowsPerSec) {
        this.readLimiter = readRowsPerSec > 0 ? new RateLimiter(readRowsPerSec) : null;
        this.writeLimiter = writeRowsPerSec > 0 ? new RateLimiter(writeRowsPerSec) : null;
    }

    /**
     * 不限速实例。
     *
     * @return throttle
     */
    public static Throttle unlimited() {
        return new Throttle(0L, 0L);
    }

    /**
     * 取写读许可（阻塞）。
     *
     * @param requested 期望行数
     * @return 实际获批行数（≥1，除非 requested ≤ 0）
     */
    public int acquireReadRows(int requested) {
        return acquire(readLimiter, requested);
    }

    /**
     * 取写许可（阻塞）。
     *
     * @param requested 期望行数
     * @return 实际获批行数
     */
    public int acquireWriteRows(int requested) {
        return acquire(writeLimiter, requested);
    }

    /**
     * 通用获取。
     *
     * @param limiter   令牌桶（null = 不限速）
     * @param requested 期望行数
     * @return 获批行数
     */
    private int acquire(RateLimiter limiter, int requested) {
        if (requested <= 0) {
            return 0;
        }
        if (limiter == null) {
            return requested;
        }
        return limiter.acquireBlocking(requested);
    }

    /**
     * 运行时调整限速（≤1s 生效）。
     *
     * <p>支持三种方向（MIG-05 要求"全向可调"）：
     * <ul>
     *   <li>限速 → 另一个限速值：调整既有令牌桶的速率；</li>
     *   <li>限速 → 不限速（{@code <=0}）：速率置 0，令牌桶立即放行；</li>
     *   <li>不限速 → 限速（构造时桶为 null）：<b>现场建桶</b>。
     *       {@code // ADDED: MIG-05 要求"任务运行中从不限速改为限速"立即生效；
     *       旧实现忽略这种情况，接口会返回"已生效"却毫无效果（谎报）。}</li>
     * </ul>
     *
     * <p><b>已知边界</b>：正在 {@code acquireBlocking} 中等待的线程不会被立即唤醒，
     * 最坏多等一个令牌周期。调高限速时表现为"稍慢一点生效"，不丢数据，
     * 因此不为此改写令牌桶的等待策略。
     *
     * @param readRowsPerSec  读限速（≤0 改为不限速）
     * @param writeRowsPerSec 写限速（≤0 改为不限速）
     */
    public synchronized void updateLimits(long readRowsPerSec, long writeRowsPerSec) {
        this.readLimiter = applyLimit(this.readLimiter, readRowsPerSec);
        this.writeLimiter = applyLimit(this.writeLimiter, writeRowsPerSec);
    }

    /**
     * 对单个令牌桶应用新速率（必要时建桶）。
     *
     * @param current    现有桶（null 表示当前不限速）
     * @param rowsPerSec 新速率（≤0 表示改为不限速）
     * @return 应用后的桶（null 表示不限速）
     */
    private static RateLimiter applyLimit(RateLimiter current, long rowsPerSec) {
        if (rowsPerSec <= 0L) {
            if (current != null) {
                current.updateRate(0L);
            }
            return current;
        }
        if (current == null) {
            return new RateLimiter(rowsPerSec);
        }
        current.updateRate(rowsPerSec);
        return current;
    }

    /** @return 读限速（0 表示不限速） */
    public long readLimit() {
        return readLimiter == null ? 0L : readLimiter.permitsPerSecond();
    }

    /** @return 写限速（0 表示不限速） */
    public long writeLimit() {
        return writeLimiter == null ? 0L : writeLimiter.permitsPerSecond();
    }

    @Override
    public String toString() {
        return "Throttle[read=" + readLimit() + "/s,write=" + writeLimit() + "/s]";
    }
}
