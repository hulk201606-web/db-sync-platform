package com.dbsync.common.exception;

/**
 * 分片租约丢失（被其它实例接管或已过期）。抛出后 worker 必须立即中止，不得再写 checkpoint。
 */
public class LeaseLostException extends DbSyncException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造。
     *
     * @param shardId 分片 ID
     * @param owner   期望的持有者
     */
    public LeaseLostException(long shardId, String owner) {
        super(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, null, "shard=" + shardId + " owner=" + owner);
        with("shardId", shardId);
        with("owner", owner);
    }
}
