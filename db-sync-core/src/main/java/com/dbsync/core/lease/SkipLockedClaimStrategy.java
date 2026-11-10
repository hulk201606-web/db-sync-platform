package com.dbsync.core.lease;

import com.dbsync.common.enums.ClaimStrategy;
import com.dbsync.common.model.Shard;
import com.dbsync.metadata.dao.ShardDao;
import java.time.Duration;
import java.util.Optional;

/**
 * 首选策略：{@code SELECT ... ORDER BY shard_no LIMIT 1 FOR UPDATE SKIP LOCKED}。
 *
 * <p>优点：O(1) 拿到可用行、无冲突、无重试；四个实例同时抢也不会互相阻塞。
 * 适用：MySQL 8.0+ / PostgreSQL 9.5+ / Oracle 11g+ / DB2 LUW 11.5。
 * <p>不支持时 {@code ShardDao} 会<b>运行时自动降级</b>为乐观 CAS（并打 WARN），不会让启动失败。
 */
public class SkipLockedClaimStrategy implements ShardClaimStrategy {

    private final ShardDao shardDao;

    /**
     * 构造。
     *
     * @param shardDao 分片 DAO
     */
    public SkipLockedClaimStrategy(ShardDao shardDao) {
        this.shardDao = shardDao;
    }

    @Override
    public Optional<Shard> claimOne(long taskId, String owner, Duration lease) {
        return shardDao.claimOne(taskId, owner, lease, ClaimStrategy.SKIP_LOCKED);
    }

    @Override
    public String name() {
        return "SKIP_LOCKED";
    }
}
