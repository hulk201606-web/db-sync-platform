package com.dbsync.core.lease;

import com.dbsync.common.enums.ClaimStrategy;
import com.dbsync.common.model.Shard;
import com.dbsync.metadata.dao.ShardDao;
import java.time.Duration;
import java.util.Optional;

/**
 * 兜底策略：无锁乐观 CAS。
 *
 * <p>{@code UPDATE ... WHERE id=? AND version=? AND state IN (...)}，{@code affectedRows==1} 即抢到。
 * 任何元数据库都能跑（H2/MySQL/PG/Oracle/DM/DB2 通用），代价是每次只能试一行、
 * 高并发下会多几次空转。
 */
public class OptimisticCasClaimStrategy implements ShardClaimStrategy {

    private final ShardDao shardDao;

    /**
     * 构造。
     *
     * @param shardDao 分片 DAO
     */
    public OptimisticCasClaimStrategy(ShardDao shardDao) {
        this.shardDao = shardDao;
    }

    @Override
    public Optional<Shard> claimOne(long taskId, String owner, Duration lease) {
        return shardDao.claimOne(taskId, owner, lease, ClaimStrategy.OPTIMISTIC_CAS);
    }

    @Override
    public String name() {
        return "OPTIMISTIC_CAS";
    }
}
