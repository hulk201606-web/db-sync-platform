package com.dbsync.migration;

import com.dbsync.common.enums.DeadLetterSourceType;
import com.dbsync.common.enums.DeadLetterStatus;
import com.dbsync.common.enums.ShardState;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.DeadLetter;
import com.dbsync.common.model.Shard;
import com.dbsync.common.util.HashUtil;
import com.dbsync.common.util.SnowflakeId;
import com.dbsync.metadata.dao.DeadLetterDao;
import com.dbsync.metadata.dao.ShardDao;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 死信服务（创建 / 幂等重放 / 带理由丢弃 / 计数）。
 *
 * <p><b>为什么要死信</b>：毒数据（超长字符串、非法日期、约束冲突）会让同一个分片
 * 每次都失败。没有死信机制，唯一的出路是"把整个任务停掉"；
 * 有了死信，失败的那一批被隔离，其余分片继续跑，运维修完数据再重放（PRD MIG-08）。
 *
 * <p><b>幂等重放的三层保障</b>：
 * <ol>
 *   <li>状态机：{@code OPEN → REPLAYING → REPLAYED}，{@code markReplaying} 是 CAS，
 *       同一死信并发重放只有一个实例能进；</li>
 *   <li>终态短路：已是 {@code REPLAYED}/{@code DISCARDED} 直接返回
 *       {@link ReplayResult#duplicate}，不重复写数据；</li>
 *   <li>数据层幂等：即便前两层都被绕过，{@link ReplayApplier} 也必须用 upsert
 *       （主键相同即覆盖），因此"重放两次"与"重放一次"的目标库状态完全一致。</li>
 * </ol>
 */
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final DeadLetterDao deadLetterDao;
    private final ShardDao shardDao;
    private final ReplayApplier applier;
    private final SnowflakeId idGenerator;

    /**
     * 构造。
     *
     * @param deadLetterDao 死信 DAO
     * @param shardDao      分片 DAO（SHARD 死信重放时把分片放回 PENDING）
     * @param applier       载荷重放器（必须幂等；可为 null，此时仅支持"分片重跑"式重放）
     */
    public DeadLetterService(DeadLetterDao deadLetterDao, ShardDao shardDao,
                             ReplayApplier applier) {
        this.deadLetterDao = deadLetterDao;
        this.shardDao = shardDao;
        this.applier = applier;
        this.idGenerator = new SnowflakeId(SnowflakeId.defaultWorkerId());
    }

    /**
     * 新建死信（id 缺省时自动分配）。
     *
     * @param dl 死信
     */
    public void create(DeadLetter dl) {
        DeadLetter letter = dl;
        if (letter.getId() <= 0L) {
            letter = new DeadLetter(idGenerator.nextId(), dl.getTaskId(), dl.getTableMappingId(),
                    dl.getSourceType(), dl.getRefId(), dl.getPkJson(), dl.getPayloadJson(),
                    dl.getErrorCode(), dl.getErrorMsg(), dl.getStackDigest(),
                    dl.getStatus() == null ? DeadLetterStatus.OPEN : dl.getStatus(), dl.getRetry());
        }
        deadLetterDao.insert(letter);
        log.warn("新增死信 id={} task={} source={} ref={} code={}", letter.getId(),
                letter.getTaskId(), letter.getSourceType(), letter.getRefId(),
                letter.getErrorCode());
    }

    /**
     * 分片失败转死信（{@code ShardWorker} 重试耗尽时调用）。
     *
     * @param shard         分片
     * @param tableMappingId 表映射 ID
     * @param message       错误摘要
     * @param cause         根因（用于生成指纹与堆栈）
     */
    public void createShardFailure(Shard shard, long tableMappingId, String message,
                                   Throwable cause) {
        String digest = digestOf(cause);
        DeadLetter letter = new DeadLetter(0L, shard.getTaskId(), tableMappingId,
                DeadLetterSourceType.SHARD, shard.getId(),
                "{\"shardNo\":" + shard.getShardNo() + ",\"lastPk\":"
                        + (shard.getLastPk() == null ? "null" : "\"" + shard.getLastPk() + "\"")
                        + "}",
                null,
                cause instanceof DbSyncException
                        ? ((DbSyncException) cause).errorCode().code()
                        : ErrorCode.SHARD_RETRY_EXHAUSTED.code(),
                message, digest, DeadLetterStatus.OPEN, 0);
        create(letter);
    }

    /**
     * 幂等重放。
     *
     * @param deadLetterId 死信 ID
     * @param operator     操作人
     * @return 重放结果
     */
    public ReplayResult replay(long deadLetterId, String operator) {
        DeadLetter letter = deadLetterDao.findById(deadLetterId)
                .orElseThrow(() -> new DbSyncException(ErrorCode.PARAM_INVALID,
                        "死信不存在：" + deadLetterId));
        if (letter.getStatus() == DeadLetterStatus.REPLAYED
                || letter.getStatus() == DeadLetterStatus.DISCARDED) {
            return ReplayResult.duplicate(deadLetterId, letter.getStatus());
        }
        // CAS：只有 OPEN 能被置为 REPLAYING，防止两个运维同时点"重放"
        int claimed = deadLetterDao.markReplaying(deadLetterId, operator);
        if (claimed == 0) {
            return ReplayResult.inProgress(deadLetterId);
        }
        try {
            int rows = applyPayload(letter);
            deadLetterDao.markReplayed(deadLetterId);
            log.info("死信重放成功 id={} operator={} rows={}", deadLetterId, operator, rows);
            return ReplayResult.applied(deadLetterId, rows);
        } catch (RuntimeException e) {
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            deadLetterDao.reopen(deadLetterId, reason);
            log.warn("死信重放失败 id={} operator={} reason={}", deadLetterId, operator, reason);
            return ReplayResult.failed(deadLetterId, reason);
        }
    }

    /**
     * 丢弃死信（必须给理由；"不写理由的丢弃"等于删除证据，架构 §8.4 明确禁止）。
     *
     * @param deadLetterId 死信 ID
     * @param operator     操作人
     * @param reason       理由
     */
    public void discard(long deadLetterId, String operator, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "丢弃死信必须填写理由");
        }
        Optional<DeadLetter> found = deadLetterDao.findById(deadLetterId);
        if (found.isEmpty()) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "死信不存在：" + deadLetterId);
        }
        DeadLetterStatus status = found.get().getStatus();
        if (status == DeadLetterStatus.DISCARDED || status == DeadLetterStatus.REPLAYED) {
            // 终态不再变更：丢弃一条已重放成功的死信会让 Gate 指标"看起来更好"，属于数据造假
            throw new DbSyncException(ErrorCode.ILLEGAL_TRANSITION,
                    "死信已是终态 " + status + "，不允许再丢弃：" + deadLetterId);
        }
        deadLetterDao.markDiscarded(deadLetterId, reason, operator);
        log.warn("死信被丢弃 id={} operator={} reason={}", deadLetterId, operator, reason);
    }

    /**
     * 统计未处理死信数（Gate 指标 OPEN_DEAD_LETTERS）。
     *
     * @param taskId 任务 ID
     * @return 数量
     */
    public long countOpen(long taskId) {
        return deadLetterDao.countOpen(taskId);
    }

    /**
     * 应用载荷。
     *
     * <p>SHARD 来源的死信没有"单行载荷"可重放 —— 它代表的是一整个区间失败。
     * 这类死信的正确重放方式是<b>把分片放回 PENDING</b>，由 {@code ShardDispatcher}
     * 重新抢占执行（断点仍在最后一次提交处，因此不会重复搬数据）。
     *
     * @param letter 死信
     * @return 影响行数（分片重跑记 0）
     */
    private int applyPayload(DeadLetter letter) {
        if (letter.getSourceType() == DeadLetterSourceType.SHARD) {
            Long shardId = letter.getRefId();
            if (shardId == null) {
                throw new DbSyncException(ErrorCode.PARAM_INVALID,
                        "SHARD 死信缺少 ref_id：" + letter.getId());
            }
            Shard shard = shardDao.findById(shardId)
                    .orElseThrow(() -> new DbSyncException(ErrorCode.PARAM_INVALID,
                            "分片不存在：" + shardId));
            if (shard.getState() == ShardState.SUCCESS) {
                // 分片已经被别的路径跑完了：重放无事可做，但也不算失败
                return 0;
            }
            shardDao.resetPending(shardId);
            return 0;
        }
        if (applier == null) {
            throw new DbSyncException(ErrorCode.EXTERNAL_NOT_IMPLEMENTED,
                    "未配置 ReplayApplier，无法重放 CHANGE 类型死信：" + letter.getId());
        }
        return applier.apply(letter);
    }

    /**
     * 异常指纹（同因归并：按 stack_digest 分组展示，避免一次事故刷出 10 万条死信）。
     *
     * @param e 异常（可 null）
     * @return 十六进制指纹
     */
    static String digestOf(Throwable e) {
        if (e == null) {
            return "0";
        }
        String raw = e.getClass().getName() + '|' + e.getMessage();
        return Long.toHexString(HashUtil.fnv1a64(raw));
    }

    /**
     * 载荷重放器（由 {@code db-sync-cdc} 提供实现：把变更载荷按主键 upsert 到目标库）。
     *
     * <p>// ADDED: T26 的签名只写了"幂等重放"，没有说重放靠什么把数据写回去。
     * 用回调而不是让本类依赖 cdc 模块，是为了守住模块依赖方向
     * （migration 不依赖 cdc；cdc 依赖 migration 复用死信能力）。
     */
    @FunctionalInterface
    public interface ReplayApplier {

        /**
         * 幂等应用一条死信载荷。
         *
         * @param letter 死信
         * @return 影响行数
         */
        int apply(DeadLetter letter);
    }
}
