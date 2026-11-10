package com.dbsync.cdc;

import com.dbsync.common.enums.ScopeType;
import com.dbsync.common.model.Checkpoint;
import com.dbsync.metadata.dao.CheckpointDao;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

/**
 * 基于 {@code sync_checkpoint(scope_type='POLLER')} 的采集位点存储（<b>生产实现</b>）。
 *
 * <p>表结构复用既有 {@code sync_checkpoint}：{@code scope_no} 固定 0，
 * {@code table_mapping_id} 即映射 ID，唯一约束 {@code uk_ckpt} 保证"一表一行"，
 * 因此不需要新增表/列（V3 只加冲突策略列）。
 *
 * <h2>采集位点（重启不重复、不漏）</h2>
 * <p>触发器模式写 {@code last_log_id}，时间戳模式写 {@code last_commit_ts}；
 * 两列 V1 就有。轮询器每轮开头读它、成功入队后写它，因此"进程重启"不会造成重放，
 * 也不需要任何"启动预热"（预热漏一个映射就漏一整张表）。
 *
 * <h2>多实例下的采集权（含局限，诚实说明）</h2>
 * <p>{@link #tryClaim} 把位点行当作<b>每表一把的租约</b>：靠 {@code updated_at}
 * 判断"是否已空闲一个窗口"，用单条条件 UPDATE 原子完成，因此：
 * <ul>
 *   <li>同一实例按周期再认领 → 窗口刚过，继续采；</li>
 *   <li>另一实例在窗口内认领 → 命中 0 行，本轮直接跳过（{@code log.debug}）；</li>
 *   <li>持有者宕机 → 最多一个窗口后另一实例接手，不需要选主。</li>
 * </ul>
 * <p><b>局限</b>：若单轮采集耗时<b>超过</b>窗口，另一实例可能在上一轮未结束时抢到认领，
 * 同一批源日志被采两遍。这<b>不影响数据正确性</b>（应用层 upsert/delete 幂等，
 * 架构 I4 本身就是"允许重复应用、绝不丢失"），只多花一遍 IO 与几行
 * {@code sync_change_log}。要彻底杜绝需要给 {@code sync_change_log} 加
 * {@code (task_id, table_mapping_id, seq)} 唯一约束（建表变更，本期不做）。
 * 因此窗口默认取 5s ≈ 默认轮询周期：正常轮转不受影响；只有"双实例同时起"这类异常
 * 才会退化为偶发重复采集，而不会静默丢变更。
 */
public class CheckpointCursorStore implements PollerCursorStore {

    private static final Logger log = LoggerFactory.getLogger(CheckpointCursorStore.class);

    /** 采集位点固定使用的 scope 号（每张映射一行，不需要更多维度）。 */
    public static final int POLLER_SCOPE_NO = 0;

    /** 默认采集权窗口：与默认轮询周期一致（5s），理由见类注释"局限"。 */
    public static final Duration DEFAULT_CLAIM_WINDOW = Duration.ofSeconds(5L);

    private final CheckpointDao checkpointDao;
    private final Duration claimWindow;

    /**
     * 构造（采集权窗口取 {@link #DEFAULT_CLAIM_WINDOW}）。
     *
     * @param checkpointDao 断点 DAO
     */
    public CheckpointCursorStore(CheckpointDao checkpointDao) {
        this(checkpointDao, DEFAULT_CLAIM_WINDOW);
    }

    /**
     * 构造。
     *
     * @param checkpointDao 断点 DAO
     * @param claimWindow   采集权窗口（应 ≈ 轮询周期；null/非正时取默认）
     */
    public CheckpointCursorStore(CheckpointDao checkpointDao, Duration claimWindow) {
        if (checkpointDao == null) {
            throw new IllegalArgumentException("CheckpointCursorStore 需要 CheckpointDao");
        }
        this.checkpointDao = checkpointDao;
        this.claimWindow = claimWindow == null || claimWindow.isNegative() || claimWindow.isZero()
                ? DEFAULT_CLAIM_WINDOW : claimWindow;
    }

    /**
     * 读位点。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @return 位点（无记录返回 {@link Cursor#empty()}）
     */
    @Override
    public Cursor load(long taskId, long tableMappingId) {
        Optional<Checkpoint> stored = checkpointDao.load(taskId, tableMappingId, ScopeType.POLLER,
                POLLER_SCOPE_NO);
        if (stored.isEmpty()) {
            return Cursor.empty();
        }
        Checkpoint ckpt = stored.get();
        long logId = ckpt.getLastLogId() == null ? 0L : ckpt.getLastLogId().longValue();
        return new Cursor(logId, ckpt.getLastCommitTs(), ckpt.getVersion());
    }

    /**
     * 认领本轮采集权（抢不到就跳过，<b>不抛异常</b>）。
     *
     * <p>不抛异常是刻意的：认领失败只说明"这一轮由别的实例做"，
     * 是正常轮转，不是故障。若抛异常会触发退避，把本该接手的那一轮越推越远。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @param window         采集权窗口（null/非正时用构造时给的默认值）
     * @return true 表示本轮由本实例采集
     */
    @Override
    public boolean tryClaim(long taskId, long tableMappingId, Duration window) {
        Optional<Checkpoint> stored = checkpointDao.load(taskId, tableMappingId, ScopeType.POLLER,
                POLLER_SCOPE_NO);
        if (stored.isEmpty()) {
            return claimByCreating(taskId, tableMappingId);
        }
        Duration effective = window == null || window.isNegative() || window.isZero()
                ? claimWindow : window;
        Instant idleBefore = Instant.now().minus(effective);
        boolean claimed = checkpointDao.claimIfIdle(taskId, tableMappingId, ScopeType.POLLER,
                POLLER_SCOPE_NO, idleBefore) == 1;
        if (!claimed) {
            log.debug("采集权在窗口内被持有，本轮跳过 task={} mapping={}", taskId, tableMappingId);
        }
        return claimed;
    }

    /**
     * 首次认领：把"建位点行"本身当作认领（唯一键冲突 = 别的实例抢先）。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @return true 表示本实例建行成功，本轮归我
     */
    private boolean claimByCreating(long taskId, long tableMappingId) {
        // lastLogId 写 0 而不是 null：0 = "已初始化但还没采过"，
        // 而 null 会同时表示"没初始化"和"没采过"，运维读库时分不清。
        Checkpoint initial = new Checkpoint(taskId, tableMappingId, ScopeType.POLLER,
                POLLER_SCOPE_NO, null, null, Long.valueOf(0L), Long.valueOf(0L), 0L, 0L, 0);
        try {
            checkpointDao.insertIfAbsent(initial);
            return true;
        } catch (DuplicateKeyException race) {
            // 另一实例刚好建好同一行：本轮让它做，本实例等窗口过后接手
            log.debug("采集位点行被其他实例抢先建立，本轮跳过 task={} mapping={}", taskId,
                    tableMappingId);
            return false;
        }
    }

    /**
     * 前进位点。
     *
     * <p>调用前提：本轮已由 {@link #tryClaim} 认领成功，且变更<b>已经</b>写入
     * {@code sync_change_log}（顺序见 {@code TriggerLogPoller} 类注释）。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @param lastLogId      触发器模式新位点（null 表示不更新）
     * @param lastCommitTs   时间戳模式新位点（null 表示不更新）
     */
    @Override
    public void advance(long taskId, long tableMappingId, Long lastLogId, Instant lastCommitTs) {
        Cursor current = load(taskId, tableMappingId);
        Long logId = lastLogId == null ? Long.valueOf(current.lastLogId()) : lastLogId;
        Instant commitTs = lastCommitTs == null ? current.lastCommitTs() : lastCommitTs;
        int version = current.version() < 0 ? 0 : current.version();
        // lastSeq 与 lastLogId 同步写：触发器模式下 seq 就是源日志表的 log_id
        // （见 TriggerLogPoller），两列保持一致，运维用任一列都能判断"采到哪了"。
        checkpointDao.save(new Checkpoint(taskId, tableMappingId, ScopeType.POLLER,
                POLLER_SCOPE_NO, null, commitTs, logId, logId, 0L, 0L, version));
    }
}
