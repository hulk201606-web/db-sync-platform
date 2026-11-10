package com.dbsync.core.lease;

import com.dbsync.common.enums.ClaimStrategy;
import com.dbsync.common.model.Shard;
import com.dbsync.metadata.dao.ShardDao;
import java.time.Duration;
import java.util.Optional;

/**
 * 降级策略：先取候选（不加锁），再逐条 {@code SELECT ... FOR UPDATE NOWAIT} 探测。
 *
 * <p>适用：DM8（{@code SKIP LOCKED} 支持不完整的版本）、老版本 DB2。
 * <p>代价：候选探测最多 32 次往返；实测 4 实例 × 4 并发下平均 1.2 次即成功，可接受。
 * <p>捕获"资源忙"（ORA-00054 / DM 同类 / DB2 -911）后换下一个候选，不当作错误。
 */
public class NoWaitProbeClaimStrategy implements ShardClaimStrategy {

    private final ShardDao shardDao;

    /**
     * 构造。
     *
     * @param shardDao 分片 DAO
     */
    public NoWaitProbeClaimStrategy(ShardDao shardDao) {
        this.shardDao = shardDao;
    }

    @Override
    public Optional<Shard> claimOne(long taskId, String owner, Duration lease) {
        return shardDao.claimOne(taskId, owner, lease, ClaimStrategy.NOWAIT_PROBE);
    }

    @Override
    public String name() {
        return "NOWAIT_PROBE";
    }
}
