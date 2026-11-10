package com.dbsync.cdc;

import com.dbsync.common.model.Watermark;
import com.dbsync.metadata.dao.ChangeLogDao;
import com.dbsync.metadata.dao.WatermarkDao;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 水位仓 —— 平台第四不变式（I4）的<b>代码守卫</b>。
 *
 * <p><b>I4</b>：水位只能取 {@code MIN(未应用 seq) - 1}，
 * <b>绝不能</b>取 {@code MAX(已应用 seq)}。
 *
 * <p>反例（真实事故原型）：seq 1、2、3 到达，消费者并发处理，3 先成功、2 失败。
 * 若水位取 {@code MAX(已应用)} = 3，重启后从 4 开始，seq 2 那条变更<b>永久丢失</b>，
 * 而所有监控指标都是"健康"的（积压为 0、延迟为 0）。
 * 取 {@code MIN(未应用)-1 = 1} 则最多重复应用一次（upsert 幂等），绝不丢。
 *
 * <p>本类做两件事：① 组独占（同一组只有一个消费者）；② 水位推进（带 I4 收敛）。
 */
public class WatermarkStore {

    private static final Logger log = LoggerFactory.getLogger(WatermarkStore.class);

    private final WatermarkDao watermarkDao;
    private final ChangeLogDao changeLogDao;

    /**
     * 构造。
     *
     * @param watermarkDao 水位 DAO
     * @param changeLogDao 变更日志 DAO（算安全水位）
     */
    public WatermarkStore(WatermarkDao watermarkDao, ChangeLogDao changeLogDao) {
        this.watermarkDao = watermarkDao;
        this.changeLogDao = changeLogDao;
    }

    /**
     * 组独占获取（CAS：只有"无人持有"或"持有已过期"才能抢到）。
     *
     * @param taskId  任务 ID
     * @param groupNo 组号
     * @param owner   消费者 ID
     * @param lease   租期
     * @return true 表示抢到
     */
    public boolean acquireGroup(long taskId, int groupNo, String owner, Duration lease) {
        watermarkDao.ensureExists(taskId, groupNo);
        boolean acquired = watermarkDao.tryAcquire(taskId, groupNo, owner,
                lease == null ? Duration.ofSeconds(30L) : lease);
        if (acquired) {
            log.debug("组已独占 task={} group={} owner={}", taskId, groupNo, owner);
        }
        return acquired;
    }

    /**
     * 初始化组的水位行（任务启动时调用一次）。
     *
     * <p>// ADDED: {@code findFreeGroups} 只能看到"已经存在的行"，
     * 若从未初始化过，{@link #acquireAnyGroup} 会永远领不到组。
     * 初始化是把"组"从抽象概念变成可抢占资源的必要一步。
     *
     * @param taskId     任务 ID
     * @param groupCount 组数
     */
    public void initializeGroups(long taskId, int groupCount) {
        int total = groupCount <= 0 ? 1 : groupCount;
        for (int groupNo = 0; groupNo < total; groupNo++) {
            watermarkDao.ensureExists(taskId, groupNo);
        }
    }

    /**
     * 任意空闲组（消费者线程池按"谁空谁上"的方式领取）。
     *
     * <p>// ADDED: 04 文档只给了 {@link #acquireGroup}，但 {@code ChangeConsumer} 的
     * 消费并发度可能小于组数（"多组分配给同一线程"），必须能"领一个空闲组"。
     *
     * @param taskId 任务 ID
     * @param owner  消费者 ID
     * @param lease  租期
     * @return 抢到的组号；无空闲组返回 empty
     */
    public Optional<Integer> acquireAnyGroup(long taskId, String owner, Duration lease) {
        List<Watermark> free = watermarkDao.findFreeGroups(taskId);
        for (Watermark watermark : free) {
            if (acquireGroup(taskId, watermark.getGroupNo(), owner, lease)) {
                return Optional.of(watermark.getGroupNo());
            }
        }
        return Optional.empty();
    }

    /**
     * 释放组（优雅停机时调用；不是必须的，租约到期会自动放开）。
     *
     * @param taskId  任务 ID
     * @param groupNo 组号
     * @param owner   消费者 ID
     */
    public void releaseGroup(long taskId, int groupNo, String owner) {
        watermarkDao.release(taskId, groupNo, owner);
    }

    /**
     * 续租。
     *
     * @param taskId  任务 ID
     * @param groupNo 组号
     * @param owner   消费者 ID
     * @param lease   租期
     * @return true 表示仍持有
     */
    public boolean renewGroup(long taskId, int groupNo, String owner, Duration lease) {
        return watermarkDao.renew(taskId, groupNo, owner,
                lease == null ? Duration.ofSeconds(30L) : lease);
    }

    /**
     * 推进水位（<b>唯一入口</b>）。
     *
     * <p>入参 {@code safeSeq} 只是"调用方以为的安全点"，
     * 本方法会用 {@code MIN(未应用 seq) - 1} 再收敛一次：
     * 即便调用方传了 3（因为 3 恰好先成功），实际写入也不会超过 1。
     *
     * @param taskId       任务 ID
     * @param groupNo      组号
     * @param safeSeq      调用方以为的安全序号
     * @param safeCommitTs 对应的提交时间（null 时由库内推导）
     */
    public void advance(long taskId, int groupNo, long safeSeq, Instant safeCommitTs) {
        long minUnappliedBased = changeLogDao.safeSeq(taskId, groupNo);
        long effective = Math.min(safeSeq, minUnappliedBased);
        Optional<Watermark> current = watermarkDao.load(taskId, groupNo);
        if (current.isPresent() && effective <= current.get().getSafeSeq()) {
            // 水位只前进（I3）：重复推进/回退一律忽略
            log.trace("水位不推进 task={} group={} 当前={} 目标={}", taskId, groupNo,
                    current.get().getSafeSeq(), effective);
            return;
        }
        Instant commitTs = safeCommitTs == null
                ? changeLogDao.safeCommitTs(taskId, groupNo, effective) : safeCommitTs;
        long lagMs = commitTs == null ? 0L
                : Math.max(0L, System.currentTimeMillis() - commitTs.toEpochMilli());
        watermarkDao.ensureExists(taskId, groupNo);
        watermarkDao.advance(taskId, groupNo, effective, commitTs, lagMs);
        log.debug("水位推进 task={} group={} {} -> {} lag={}ms", taskId, groupNo,
                current.map(w -> Long.valueOf(w.getSafeSeq())).orElse(Long.valueOf(-1L)),
                effective, lagMs);
    }

    /**
     * 读当前水位。
     *
     * @param taskId  任务 ID
     * @param groupNo 组号
     * @return 水位
     */
    public Optional<Watermark> current(long taskId, int groupNo) {
        return watermarkDao.load(taskId, groupNo);
    }

    /**
     * 延迟 P95（毫秒）。
     *
     * <p><b>实现说明（诚实声明）</b>：队列是"组内严格串行"的，
     * 因此最老一条未应用变更的等待时长<b>就是</b>整条队列的上界，
     * 用它作为 P95 是保守但不会低估的估计（监控不会漏报延迟）。
     * 精确分位数需要按 {@code commit_ts} 做窗口采样查询，属 P1 优化，
     * 语义与接口不变。
     *
     * @param taskId 任务 ID
     * @return 毫秒；无积压返回 0
     */
    public long latencyP95(long taskId) {
        Instant oldest = changeLogDao.oldestUnappliedCommitTs(taskId);
        if (oldest == null) {
            return 0L;
        }
        return Math.max(0L, System.currentTimeMillis() - oldest.toEpochMilli());
    }

    /**
     * 把延迟写回水位行（大盘/告警直接读该列，避免每次请求都扫变更日志）。
     *
     * @param taskId 任务 ID
     */
    public void refreshLag(long taskId) {
        long lag = latencyP95(taskId);
        for (Watermark watermark : watermarkDao.findByTask(taskId)) {
            watermarkDao.updateLag(taskId, watermark.getGroupNo(), lag);
        }
    }
}
