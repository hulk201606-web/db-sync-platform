package com.dbsync.api.schedule;

import com.dbsync.common.enums.ShardState;
import com.dbsync.common.model.Shard;
import com.dbsync.common.model.SyncTask;
import com.dbsync.metadata.dao.InstanceDao;
import com.dbsync.metadata.dao.ShardDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 租约回收任务（T44：过期租约回收）。
 *
 * <p><b>关键要求（T44 验收 2）</b>：回收判据必须与 {@code ShardDao.claimOne} <b>完全一致</b>，
 * 否则会出现"reaper 认为可回收、但 claim 又抢不到"的死区。
 * 因此本类<b>不自己拼 SQL、不自己算过期时间</b>，而是调用
 * {@link ShardDao#releaseExpiredToPending(long)}——它的 WHERE 条件
 * （{@code state IN ('CLAIMED','RUNNING') AND lease_expire_at < now}）正是
 * {@code claimOne} 可抢占判据的第二个分支，回收后 {@code state='PENDING'}，
 * 任何实例的下一次 {@code claimOne} 都能立刻抢到（验收 2）。
 *
 * <p>与 {@code MigrationEngine.recover} 的区别：那边用
 * {@link ShardDao#takeOverExpired(long, String, java.time.Duration)} 直接接管
 * （实例刚启动、要立刻续跑），这里放回公池（周期回收、要重新均衡）。
 *
 * <p>回收范围：所有 {@code RUNNING} 状态的任务。任务不在跑时即使有残留过期分片，
 * 也留待下次启动时 {@code recover} 处理（避免抢走"用户手动暂停"的分片）。
 */
public class LeaseReaperJob {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaperJob.class);

    private final InstanceDao instanceDao;
    private final SyncTaskDao syncTaskDao;
    private final ShardDao shardDao;
    private final String instanceId;

    /**
     * 构造。
     *
     * @param instanceDao 实例 DAO
     * @param syncTaskDao 任务 DAO
     * @param shardDao    分片 DAO（回收逻辑的唯一实现处）
     * @param instanceId  本实例 ID
     */
    public LeaseReaperJob(InstanceDao instanceDao, SyncTaskDao syncTaskDao, ShardDao shardDao,
                          String instanceId) {
        this.instanceDao = instanceDao;
        this.syncTaskDao = syncTaskDao;
        this.shardDao = shardDao;
        this.instanceId = instanceId;
    }

    /**
     * 执行一次回收。
     *
     * <p>回收动作是"放回 PENDING"（{@link ShardDao#releaseExpiredToPending}），
     * 而不是"直接换持有者"：这样回收后所有实例都能公平抢占，
     * 不会出现"第一个扫描到的实例把整批分片全拿走"的负载倾斜。
     *
     * @return 本次回收的分片数
     */
    public int runOnce() {
        int reclaimed = 0;
        try {
            for (SyncTask task : syncTaskDao.findByState(
                    com.dbsync.common.enums.TaskState.RUNNING)) {
                reclaimed += shardDao.releaseExpiredToPending(task.getId());
            }
            if (reclaimed > 0) {
                log.warn("租约回收完成 instance={} 回收分片={}", instanceId, reclaimed);
            }
        } catch (RuntimeException e) {
            log.warn("租约回收失败（下个周期重试）", e);
        }
        return reclaimed;
    }

    /**
     * 只读：列出某任务的"疑似过期"分片（大盘与排障用）。
     *
     * <p><b>判据说明（避免注释说谎）</b>：{@code Shard} 未暴露 {@code lease_expire_at}，
     * 本方法<b>并不判断心跳是否存活、也不判断租约是否过期</b>，只是把"持有者实例台账
     * 已不存在"（{@code instanceDao.findById(owner).isEmpty()}，例如注销 / 被
     * {@code removeStale} 清理）的分片近似列为疑似过期，供排障展示。
     * 真正决定能否回收的是 {@link ShardDao#releaseExpiredToPending(long)} 的
     * {@code lease_expire_at < now} 判据（见 {@link #runOnce()}），两套口径不可混用。
     * 若要把本列表改成按租约过期判定，需要给 {@code Shard} 暴露 {@code lease_expire_at}
     * 并与 {@code claimOne} 共用同一表达式，改动面较大，本期只修正注释不改判据。
     *
     * @param taskId 任务 ID
     * @param now    当前时间基准（保留参数以兼容既有调用方；当前实现不依赖它）
     * @return 疑似过期分片列表
     */
    public List<Shard> listExpired(long taskId, java.time.Instant now) {
        List<Shard> expired = new ArrayList<>();
        for (Shard shard : shardDao.findByTaskAndState(taskId, ShardState.RUNNING)) {
            String owner = shard.getLeaseOwner();
            if (owner == null || owner.isBlank() || owner.equals(instanceId)) {
                continue;
            }
            // 以"持有者实例台账是否还在"近似：记录已不存在即视为疑似过期
            if (instanceDao.findById(owner).isEmpty()) {
                expired.add(shard);
            }
        }
        return expired;
    }
}
