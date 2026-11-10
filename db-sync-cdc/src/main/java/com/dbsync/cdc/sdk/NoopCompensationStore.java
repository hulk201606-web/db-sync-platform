package com.dbsync.cdc.sdk;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 空补偿实现：什么都不记（业务方已有可靠本地队列时使用）。
 *
 * <p>用途与风险：
 * <ul>
 *   <li>{@link #record} 返回一个递增的假 ID，{@link #listPending} 永远为空，
 *       {@code mark*} 永远返回 false——语义是"没有补偿记录可管理"；</li>
 *   <li>配上 {@link SdkWriteMode#ASYNC} 时，写失败就只留日志与计数
 *       （等价于 {@link WriteOutcome#DROPPED} 但不算异常路径）；</li>
 *   <li>因此它只适合"业务方自己的队列本身就是可靠存储"的场景，
 *       默认不推荐——默认应使用 {@link JdbcCompensationStore}。</li>
 * </ul>
 *
 * <p>线程安全：内部只有一个 {@link AtomicLong} 计数器。
 */
public final class NoopCompensationStore implements CompensationStore {

    private final AtomicLong sequence = new AtomicLong(0L);

    @Override
    public long record(CompensationRecord record) {
        return sequence.incrementAndGet();
    }

    @Override
    public List<CompensationRecord> listPending(long taskId, int limit) {
        return List.of();
    }

    @Override
    public boolean markReplayed(long id) {
        return false;
    }

    @Override
    public boolean markDiscarded(long id, String reason) {
        return false;
    }

    @Override
    public boolean markRetry(long id, String lastError, Instant nextRetryAt) {
        return false;
    }
}
