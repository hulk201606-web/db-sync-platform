package com.dbsync.api.metrics;

import com.dbsync.cdc.WatermarkStore;
import com.dbsync.common.enums.ChangeStatus;
import com.dbsync.common.model.Watermark;
import com.dbsync.core.port.IncrementalMetricsPort;
import com.dbsync.metadata.dao.ChangeLogDao;
import com.dbsync.metadata.dao.WatermarkDao;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 增量指标适配器：把 cdc 模块的 {@link WatermarkStore} + 元数据库 DAO
 * 适配成切流模块所需的 {@link IncrementalMetricsPort}。
 *
 * <p>为什么需要它：cutover 不能依赖 cdc（架构 §3.1 禁止模块环），
 * 所以只能依赖端口；装配点在 {@code db-sync-server} 的 {@code EngineBeanConfig}（T47）。
 *
 * <p><b>约定</b>：所有方法"永不抛异常"（端口契约），取不到数据返回 0/null，
 * 由 Gate 判为"无数据不通过"。
 */
public class IncrementalMetricsAdapter implements IncrementalMetricsPort {

    private static final Logger log = LoggerFactory.getLogger(IncrementalMetricsAdapter.class);

    /** 默认窗口（与 Gate 口径一致）。 */
    private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(15L);

    private final WatermarkStore watermarkStore;
    private final ChangeLogDao changeLogDao;
    private final WatermarkDao watermarkDao;

    /**
     * 构造。
     *
     * @param watermarkStore 水位线存储（提供 P95 延迟）
     * @param changeLogDao   变更日志 DAO（提供积压与失败率）
     * @param watermarkDao   水位线 DAO（提供最近一致提交时间）
     */
    public IncrementalMetricsAdapter(WatermarkStore watermarkStore, ChangeLogDao changeLogDao,
                                     WatermarkDao watermarkDao) {
        this.watermarkStore = watermarkStore;
        this.changeLogDao = changeLogDao;
        this.watermarkDao = watermarkDao;
    }

    @Override
    public long latencyP95(long taskId) {
        try {
            return watermarkStore.latencyP95(taskId);
        } catch (RuntimeException e) {
            log.debug("latencyP95 读取失败 task={} err={}", taskId, e.toString());
            return 0L;
        }
    }

    @Override
    public long backlog(long taskId) {
        try {
            return changeLogDao.backlog(taskId);
        } catch (RuntimeException e) {
            log.debug("backlog 读取失败 task={} err={}", taskId, e.toString());
            return 0L;
        }
    }

    @Override
    public double failRate(long taskId, Duration window) {
        try {
            Duration effective = window == null || window.isNegative() || window.isZero()
                    ? DEFAULT_WINDOW : window;
            Instant since = Instant.now().minus(effective);
            long failed = changeLogDao.countByStatusSince(taskId, ChangeStatus.FAILED, since);
            long finished = changeLogDao.countFinishedSince(taskId, since);
            if (finished <= 0L) {
                return 0.0d;
            }
            return (double) failed / (double) finished;
        } catch (RuntimeException e) {
            log.debug("failRate 读取失败 task={} err={}", taskId, e.toString());
            return 0.0d;
        }
    }

    /**
     * 最近一次"组水位已推进到的一致提交时间"（跨组取最大）。
     *
     * <p>语义说明：它不是"最后一条 APPLIED 的 applied_at"，而是"最新可用于读的
     * commit_ts"——Gate 关心的是"增量追平到哪里"，这个口径更贴近 §11 的
     * {@code lag = now - MIN(commit_ts WHERE status='NEW')} 的反面。
     *
     * @param taskId 任务 ID
     * @return 时间；无数据返回 null
     */
    @Override
    public Instant lastApplyAt(long taskId) {
        try {
            Instant latest = null;
            for (Watermark wm : watermarkDao.findByTask(taskId)) {
                Instant ts = wm.getSafeCommitTs();
                if (ts != null && (latest == null || ts.isAfter(latest))) {
                    latest = ts;
                }
            }
            return latest;
        } catch (RuntimeException e) {
            log.debug("lastApplyAt 读取失败 task={} err={}", taskId, e.toString());
            return null;
        }
    }
}
