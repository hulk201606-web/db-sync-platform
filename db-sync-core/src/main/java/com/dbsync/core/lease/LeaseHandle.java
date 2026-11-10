package com.dbsync.core.lease;

import com.dbsync.common.model.Shard;
import java.time.Duration;

/**
 * 租约句柄：一次抢占的结果凭证。
 *
 * <p>用它而不是直接传 {@code shardId} 的原因是：
 * <ol>
 *   <li>把 {@code owner} 和 {@code shard} 绑在一起，减少"传错 owner"导致的假续租；</li>
 *   <li>把 {@code assertHeld} 收在句柄上，worker 每 N 批调一次，语义清晰；</li>
 *   <li>{@link #close()} 即释放，配合 try-with-resources 让"忘记释放"变成编译期问题。</li>
 * </ol>
 */
public final class LeaseHandle implements AutoCloseable {

    private final ShardLeaseManager manager;
    private final Shard shard;
    private final String owner;

    /**
     * 构造。
     *
     * @param manager 租约管理器
     * @param shard   分片
     * @param owner   持有者
     */
    LeaseHandle(ShardLeaseManager manager, Shard shard, String owner) {
        this.manager = manager;
        this.shard = shard;
        this.owner = owner;
    }

    /** @return 分片 */
    public Shard shard() {
        return shard;
    }

    /** @return 分片 ID */
    public long shardId() {
        return shard.getId();
    }

    /** @return 持有者 */
    public String owner() {
        return owner;
    }

    /** @return 租约时长 */
    public Duration leaseDuration() {
        return manager.leaseDuration();
    }

    /**
     * 续租。
     *
     * @return true 表示仍持有
     */
    public boolean renew() {
        return manager.renew(shard.getId(), owner);
    }

    /**
     * 校验仍持有（失败抛 {@code LeaseLostException}）。
     */
    public void assertHeld() {
        manager.assertHeld(shard.getId(), owner);
    }

    /**
     * 释放（best effort，不抛异常）。
     */
    @Override
    public void close() {
        manager.release(shard.getId(), owner);
    }

    @Override
    public String toString() {
        return "LeaseHandle[shard=" + shard.getId() + ",owner=" + owner + "]";
    }
}
