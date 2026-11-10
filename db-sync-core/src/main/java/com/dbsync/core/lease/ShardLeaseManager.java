package com.dbsync.core.lease;

import com.dbsync.common.enums.ClaimStrategy;
import com.dbsync.common.exception.LeaseLostException;
import com.dbsync.common.model.Shard;
import com.dbsync.metadata.dao.ShardDao;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 分片租约管理器（架构 §4.2 的三层保护）。
 *
 * <p><b>三层保护</b>：
 * <ol>
 *   <li>抢占层：同一分片同一时刻只有一个实例能把它置为 CLAIMED（靠 SKIP LOCKED / CAS）；</li>
 *   <li>心跳层：持有者必须续租，否则 {@code lease_expire_at} 到期后可被接管；</li>
 *   <li>写入层：每 N 批 {@link #assertHeld} 一次，租约丢了立刻停手（不靠"以为还持有"）。</li>
 * </ol>
 *
 * <p>参数默认值（架构 §4.2.5）：租约 30s、心跳 10s（= lease/3，容忍 2 次丢失）。
 * 租约<b>不得</b>> 30s，否则违反 PRD"实例宕机后 ≤30s 接管"的承诺。
 */
public class ShardLeaseManager {

    private static final Logger log = LoggerFactory.getLogger(ShardLeaseManager.class);

    private final ShardDao shardDao;
    private final ShardClaimStrategy strategy;
    private final Duration leaseDuration;
    private final Duration heartbeatInterval;

    /**
     * 构造。
     *
     * @param shardDao         分片 DAO
     * @param strategy         抢占策略
     * @param leaseDuration    租约时长（≤30s）
     * @param heartbeatInterval 心跳间隔（建议 lease/3）
     */
    public ShardLeaseManager(ShardDao shardDao, ShardClaimStrategy strategy,
                             Duration leaseDuration, Duration heartbeatInterval) {
        this.shardDao = shardDao;
        this.strategy = strategy;
        Duration lease = leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()
                ? Duration.ofSeconds(30) : leaseDuration;
        if (lease.compareTo(Duration.ofSeconds(30)) > 0) {
            log.warn("租约 {} 超过 30s，会违反“宕机后 ≤30s 接管”的承诺，已强制收敛为 30s", lease);
            lease = Duration.ofSeconds(30);
        }
        this.leaseDuration = lease;
        Duration heartbeat = heartbeatInterval == null || heartbeatInterval.isZero()
                || heartbeatInterval.isNegative() ? lease.dividedBy(3) : heartbeatInterval;
        Duration maxHeartbeat = lease.dividedBy(3);
        // 心跳必须 ≤ lease/3（容忍 2 次丢失）；租约被收敛后心跳也要跟着收敛
        this.heartbeatInterval = heartbeat.compareTo(maxHeartbeat) > 0 ? maxHeartbeat : heartbeat;
    }

    /**
     * 默认配置（30s 租约 / 10s 心跳 / SKIP_LOCKED）。
     *
     * @param shardDao 分片 DAO
     * @return 管理器
     */
    public static ShardLeaseManager defaults(ShardDao shardDao) {
        return new ShardLeaseManager(shardDao, new SkipLockedClaimStrategy(shardDao),
                Duration.ofSeconds(30), Duration.ofSeconds(10));
    }

    /**
     * 按配置的策略名建管理器。
     *
     * @param shardDao 分片 DAO
     * @param strategy 策略枚举
     * @return 策略实现
     */
    public static ShardClaimStrategy strategyOf(ShardDao shardDao, ClaimStrategy strategy) {
        ClaimStrategy chosen = strategy == null ? ClaimStrategy.SKIP_LOCKED : strategy;
        switch (chosen) {
            case NOWAIT_PROBE:
                return new NoWaitProbeClaimStrategy(shardDao);
            case OPTIMISTIC_CAS:
                return new OptimisticCasClaimStrategy(shardDao);
            case SKIP_LOCKED:
            default:
                return new SkipLockedClaimStrategy(shardDao);
        }
    }

    /**
     * 抢一个分片。
     *
     * @param taskId 任务 ID
     * @param owner  持有者
     * @return 租约句柄；无可用分片返回 empty
     */
    public Optional<LeaseHandle> claim(long taskId, String owner) {
        Optional<Shard> shard = strategy.claimOne(taskId, owner, leaseDuration);
        if (shard.isEmpty()) {
            return Optional.empty();
        }
        log.debug("抢到分片 taskId={} shard={} owner={} strategy={}", taskId,
                shard.get().getShardNo(), owner, strategy.name());
        return Optional.of(new LeaseHandle(this, shard.get(), owner));
    }

    /**
     * 续租。
     *
     * @param shardId 分片 ID
     * @param owner   持有者
     * @return 是否成功
     */
    public boolean renew(long shardId, String owner) {
        return shardDao.renewLease(shardId, owner, leaseDuration);
    }

    /**
     * 校验租约仍归自己（每 N 批调用一次）。
     *
     * @param shardId 分片 ID
     * @param owner   持有者
     * @throws LeaseLostException 租约已丢失（worker 必须立刻停止写入）
     */
    public void assertHeld(long shardId, String owner) {
        if (!shardDao.assertHeld(shardId, owner)) {
            log.warn("分片租约已丢失：shardId={} owner={}（可能已被其他实例接管，本实例必须立即停止写入）",
                    shardId, owner);
            throw new LeaseLostException(shardId, owner);
        }
    }

    /**
     * 主动释放（best effort）。
     *
     * @param shardId 分片 ID
     * @param owner   持有者
     */
    public void release(long shardId, String owner) {
        shardDao.release(shardId, owner);
    }

    /**
     * 回收过期租约（实例宕机后的接管扫描）。
     *
     * <p>与 {@link #claim} 使用同一判据（{@code lease_expire_at < now(3)}），
     * 因此不会出现"扫描扫不到、抢占也抢不到"的死区。
     *
     * @param taskId 任务 ID
     * @param owner  新持有者
     * @return 回收数量
     */
    public int takeOverExpired(long taskId, String owner) {
        return shardDao.takeOverExpired(taskId, owner);
    }

    /** @return 租约时长 */
    public Duration leaseDuration() {
        return leaseDuration;
    }

    /** @return 心跳间隔 */
    public Duration heartbeatInterval() {
        return heartbeatInterval;
    }

    /** @return 生效的策略名 */
    public String strategyName() {
        return strategy.name();
    }
}
