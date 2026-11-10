package com.dbsync.core.checkpoint;

import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.enums.ScopeType;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.Checkpoint;
import com.dbsync.common.util.PkRangeUtil;
import com.dbsync.core.write.WriteResult;
import com.dbsync.metadata.dao.CheckpointDao;
import com.dbsync.metadata.dao.ShardDao;
import com.dbsync.metadata.dao.WatermarkDao;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 断点存储 —— 平台第一不变式（I1）的<b>代码守卫</b>。
 *
 * <p><b>I1</b>：{@code commit()} 成功之后才允许推进断点。
 * 反过来（先推断点再提交）会造成"数据没进目标库但断点已前进"→ 重启后这批数据永久丢失。
 *
 * <p>本类把这条纪律写成第一行代码：
 * <pre>
 * if (!lastWrite.committed()) throw new IllegalStateException(...)
 * </pre>
 * 并且<b>不提供</b> {@code forceSave()} / {@code saveWithoutGuard()} 之类的逃生门——
 * 一旦留了门，半年后一定有人在"赶工期"时用上（架构 §4.1.3 明确要求"没有逃生门"）。
 */
public class CheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(CheckpointStore.class);

    private final CheckpointDao checkpointDao;
    private final ShardDao shardDao;
    private final WatermarkDao watermarkDao;
    private final TransactionTemplate metaTx;

    /**
     * 构造。
     *
     * @param checkpointDao 断点 DAO
     * @param shardDao      分片 DAO（用于同步 last_pk）
     * @param watermarkDao  水位 DAO（增量推进）
     * @param metaTx        元数据库事务模板
     */
    public CheckpointStore(CheckpointDao checkpointDao, ShardDao shardDao, WatermarkDao watermarkDao,
                           TransactionTemplate metaTx) {
        this.checkpointDao = checkpointDao;
        this.shardDao = shardDao;
        this.watermarkDao = watermarkDao;
        this.metaTx = metaTx;
    }

    /**
     * 读取断点。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID（GROUP/POLLER 传 0）
     * @param type           scope 类型
     * @param scopeNo        scope 号
     * @return 断点
     */
    public Optional<Checkpoint> load(long taskId, long tableMappingId, ScopeType type, int scopeNo) {
        return checkpointDao.load(taskId, tableMappingId, type, scopeNo);
    }

    /**
     * 保存断点（<b>唯一入口</b>）。
     *
     * <p>前置条件：{@code lastWrite.committed() == true}，否则抛 {@link IllegalStateException}
     * 且元数据库<b>不会有任何变化</b>。
     *
     * <p>副作用（同一元数据库事务内）：
     * <ol>
     *   <li>写 {@code sync_checkpoint}；</li>
     *   <li>SHARD scope 时同步写 {@code sync_shard.last_pk / committed_rows}，
     *       保证"断点"与"分片进度"两处永不打架。</li>
     * </ol>
     *
     * @param ckpt     断点
     * @param lastWrite 最近一次写结果（必须是已提交状态）
     */
    public void save(Checkpoint ckpt, WriteResult lastWrite) {
        if (lastWrite == null || !lastWrite.committed()) {
            // ★ 这一行就是 I1 的防线，比任何文档都有效
            throw new IllegalStateException(
                    "V1 违反：必须先提交数据再推 checkpoint（taskId=" + ckpt.getTaskId()
                            + ",scope=" + ckpt.getScopeType() + "#" + ckpt.getScopeNo() + "）");
        }
        Optional<Checkpoint> previous = checkpointDao.load(ckpt.getTaskId(),
                ckpt.getTableMappingId(), ckpt.getScopeType(), ckpt.getScopeNo());
        previous.ifPresent(before -> rejectRegression(before, ckpt));

        Boolean ok = metaTx.execute(status -> {
            int saved = checkpointDao.save(ckpt);
            if (saved == 0) {
                throw new DbSyncException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                        "断点版本冲突：taskId=" + ckpt.getTaskId() + ",scope="
                                + ckpt.getScopeType() + "#" + ckpt.getScopeNo());
            }
            if (ckpt.getScopeType() == ScopeType.SHARD) {
                // SHARD scope 的 scopeNo 是 shard_no（int），不是分片主键（雪花 ID）
                shardDao.findByTaskAndShardNo(ckpt.getTaskId(), ckpt.getScopeNo())
                        .ifPresent(shard -> shardDao.updateProgressSnapshot(shard.getId(),
                                ckpt.getLastPk(), ckpt.getCommittedRows()));
            }
            return Boolean.TRUE;
        });
        if (!Boolean.TRUE.equals(ok)) {
            throw new DbSyncException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                    "断点保存事务未提交：" + ckpt);
        }
    }

    /**
     * 增量水位推进（<b>不走</b> WriteResult 守卫）。
     *
     * <p>为什么可以不走守卫：增量按组批量推进，水位的语义是"连续已应用前缀"，
     * 由 {@code ChangeLogDao.safeSeq}（{@code MIN(seq)-1}）保证；
     * 它描述的是"已确认应用到哪里"，而不是"刚写了一批数据"。
     *
     * @param taskId       任务 ID
     * @param groupNo      组号
     * @param safeSeq      安全序号
     * @param safeCommitTs 水位对应提交时间
     */
    public void advanceWatermark(long taskId, int groupNo, long safeSeq, Instant safeCommitTs) {
        int rows = watermarkDao.advance(taskId, groupNo, safeSeq, safeCommitTs,
                safeCommitTs == null ? 0L
                        : Math.max(0L, System.currentTimeMillis() - safeCommitTs.toEpochMilli()));
        if (rows == 0) {
            // 0 行不是错误：可能是水位已经 >= safeSeq（重复推进），也可能是行不存在
            watermarkDao.ensureExists(taskId, groupNo);
            log.debug("水位未推进（可能已是最新）taskId={} group={} safeSeq={}", taskId, groupNo,
                    safeSeq);
        }
    }

    /**
     * last_pk 回退检测（I2/I3：断点只能前进）。
     *
     * @param before 之前的断点
     * @param next   待保存的断点
     */
    private void rejectRegression(Checkpoint before, Checkpoint next) {
        String previous = before.getLastPk();
        String current = next.getLastPk();
        if (previous == null || current == null || previous.equals(current)) {
            return;
        }
        RangeKind kind = guessKind(previous, current);
        if (PkRangeUtil.isRegression(previous, current, kind)) {
            throw new DbSyncException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT,
                    "断点 last_pk 回退被拒绝：from=" + previous + " to=" + current
                            + "（scope=" + next.getScopeType() + "#" + next.getScopeNo() + "）")
                    .with("taskId", next.getTaskId()).with("scopeNo", next.getScopeNo());
        }
    }

    /**
     * 猜主键类型：两边都能解析成数值则 NUMERIC，否则 STRING。
     *
     * @param a 值 a
     * @param b 值 b
     * @return 类型
     */
    private static RangeKind guessKind(String a, String b) {
        try {
            new BigDecimal(a.trim());
            new BigDecimal(b.trim());
            return RangeKind.NUMERIC;
        } catch (NumberFormatException e) {
            return RangeKind.STRING;
        }
    }
}
