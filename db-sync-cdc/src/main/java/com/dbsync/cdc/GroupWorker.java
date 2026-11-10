package com.dbsync.cdc;

import com.dbsync.common.enums.DeadLetterSourceType;
import com.dbsync.common.enums.DeadLetterStatus;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.ChangeEvent;
import com.dbsync.common.model.DeadLetter;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.util.HashUtil;
import com.dbsync.common.util.JsonUtil;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 组内串行消费者（一个组在任一时刻只有一个实例在跑）。
 *
 * <p><b>五条硬约束</b>（T29）：
 * <ol>
 *   <li>组独占：由 {@code WatermarkStore.acquireGroup} 的 CAS 保证；</li>
 *   <li>组内严格按 {@code seq} 升序逐条处理，<b>批内也不并行</b>
 *       —— 同一主键的 update/delete 顺序错乱会让目标端与源端永久不一致；</li>
 *   <li>单条失败不阻塞整组：重试耗尽后标 {@code DEAD} + 死信，<b>继续下一条</b>；</li>
 *   <li>{@code markApplied} 是批量 UPDATE（500 个 id 一条 SQL）；</li>
 *   <li><b>水位推进必须在 markApplied 之后</b>：先推水位再标应用，
 *       中途崩溃会让"水位说已处理"但"日志还是 NEW"，重启后又处理一遍
 *       （upsert 幂等所以只是白干）；反过来先标记则不会重复。</li>
 * </ol>
 */
public class GroupWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(GroupWorker.class);

    private final CdcContext ctx;
    private final int groupNo;
    private final String owner;

    /**
     * 构造（租约持有者取上下文的 owner）。
     *
     * @param ctx     上下文
     * @param groupNo 组号
     */
    public GroupWorker(CdcContext ctx, int groupNo) {
        this(ctx, groupNo, ctx.getOwner());
    }

    /**
     * 构造（显式指定本槽位的租约持有者）。
     *
     * <p>// FIXED: {@code ChangeConsumer} 用 {@code owner + "#" + slotIndex} 抢组
     * （同一实例的多个槽位必须能互相区分，否则 {@code tryAcquire} 的"本来就是我"分支
     * 会让两个槽位同时以为自己独占同一组），但续租却用了不带槽位后缀的 {@code ctx.getOwner()}，
     * 于是 {@code renew} 的 {@code consumer_owner=?} 永远不匹配 → 组消费循环立刻退出、
     * 变更一条都不会被应用。这个 bug 只有在"真的把消费者跑起来"时才会暴露，
     * 因此必须由槽位把自己的 owner 传进来。
     *
     * @param ctx     上下文
     * @param groupNo 组号
     * @param owner   租约持有者（必须与抢组时用的值完全一致）
     */
    public GroupWorker(CdcContext ctx, int groupNo, String owner) {
        this.ctx = ctx;
        this.groupNo = groupNo;
        this.owner = owner == null || owner.isBlank() ? ctx.getOwner() : owner;
    }

    /**
     * 组内消费循环（阻塞直到收到停止信号或组租约丢失）。
     */
    public void loop() {
        final long taskId = ctx.getTaskId();
        while (!ctx.stopRequested()) {
            // renewGroup 同时承担"续租"与"是否仍独占"两个职责：返回 false 就该收手
            if (!ctx.getWatermarks().renewGroup(taskId, groupNo, owner, ctx.getGroupLease())) {
                log.info("组租约丢失，停止消费 task={} group={} owner={}", taskId, groupNo, owner);
                return;
            }
            List<ChangeEvent> batch = ctx.getQueue().pollBatch(taskId, groupNo, ctx.getBatchSize());
            if (batch.isEmpty()) {
                sleep();
                continue;
            }
            drain(batch);
        }
        log.info("组消费循环退出 task={} group={}", taskId, groupNo);
    }

    @Override
    public void run() {
        loop();
    }

    /**
     * 处理一批变更（严格按 seq 顺序）。
     *
     * @param batch 批次（已按 seq 升序）
     */
    void drain(List<ChangeEvent> batch) {
        List<Long> applied = new ArrayList<>(batch.size());
        Map<String, List<Long>> skipped = new LinkedHashMap<String, List<Long>>();
        long maxSeq = -1L;
        int poison = 0;

        for (ChangeEvent event : batch) {
            maxSeq = Math.max(maxSeq, event.getSeq());
            TableMeta table = ctx.tableOf(event.getTableMappingId());
            if (table == null) {
                ctx.getQueue().markDead(event.getId(),
                        "未配置目标表元数据：tableMappingId=" + event.getTableMappingId());
                poison++;
                continue;
            }
            try {
                ApplyResult result = applyWithRetry(event, table);
                if (result.isApplied()) {
                    applied.add(Long.valueOf(event.getId()));
                } else {
                    skipped.computeIfAbsent(result.getConflictAction(),
                            k -> new ArrayList<Long>()).add(Long.valueOf(event.getId()));
                }
            } catch (RuntimeException e) {
                // 毒消息隔离：标记 DEAD + 死信，继续处理本组后续消息
                poison++;
                toDeadLetter(event, e);
            }
        }

        // ① 先批量标记已应用（500 个 id 一条 SQL）
        if (!applied.isEmpty()) {
            ctx.getQueue().markApplied(applied);
        }
        for (Map.Entry<String, List<Long>> entry : skipped.entrySet()) {
            ctx.getQueue().markSkipped(entry.getValue(), entry.getKey());
        }
        // ② 再推进水位（顺序不可交换，见类注释第 5 条）
        if (maxSeq >= 0L) {
            ctx.getWatermarks().advance(ctx.getTaskId(), groupNo, maxSeq, null);
        }
        if (log.isDebugEnabled()) {
            log.debug("组处理完成 task={} group={} applied={} skipped={} dead={}", ctx.getTaskId(),
                    groupNo, applied.size(), skipped.size(), poison);
        }
    }

    /**
     * 带重试地应用一条变更。
     *
     * <p>重试用 {@link com.dbsync.common.util.RetryTemplate}（指数退避 + 抖动），
     * 耗尽后抛 {@code RETRY_EXHAUSTED}，由 {@link #drain(List)} 转死信。
     *
     * @param event 变更
     * @param table 目标表
     * @return 应用结果
     */
    private ApplyResult applyWithRetry(ChangeEvent event, TableMeta table) {
        return ctx.getRetry().execute(() -> applyOnce(event, table), GroupWorker::isRetryable);
    }

    /**
     * 单次应用（一个事件一个事务）。
     *
     * @param event 变更
     * @param table 目标表
     * @return 应用结果
     */
    private ApplyResult applyOnce(ChangeEvent event, TableMeta table) {
        Connection connection = null;
        try {
            connection = ctx.getTargetConnections().get();
            connection.setAutoCommit(false);
            // FIXED(CDC-06)：策略必须是"这张表的策略"，不能用任务级一刀切——
            // 同一个组会混合处理不同表的变更，而 PRD 要求三种策略按表可配。
            ConflictDecision decision = ctx.getResolver().resolve(event, connection, table,
                    ctx.policyOf(event.getTableMappingId()));
            if (!decision.isApply()) {
                connection.rollback();
                log.debug("按策略跳过变更 id={} {}", event.getId(), decision.getReason());
                return ApplyResult.skipped(decision.getAction(), decision.getReason());
            }
            ApplyResult result = ctx.getApplier().apply(connection, ctx.getDialect(), table, event);
            connection.commit();
            return result;
        } catch (SQLException e) {
            rollback(connection);
            throw new DbSyncException(ErrorCode.CHANGE_APPLY_FAILED, e,
                    "应用变更失败 id=" + event.getId() + "：" + e.getMessage());
        } catch (RuntimeException e) {
            rollback(connection);
            throw e;
        } finally {
            close(connection);
        }
    }

    /**
     * 毒消息转死信。
     *
     * @param event 变更
     * @param error 异常
     */
    void toDeadLetter(ChangeEvent event, RuntimeException error) {
        String message = error instanceof DbSyncException
                ? ((DbSyncException) error).errorCode().code() + " " + error.getMessage()
                : error.getClass().getSimpleName() + ": " + error.getMessage();
        ctx.getQueue().markDead(event.getId(), message);
        if (ctx.getDeadLetters() == null) {
            log.warn("未配置死信端口，仅记录日志：changeId={} {}", event.getId(), message);
            return;
        }
        DeadLetter letter = new DeadLetter(0L, ctx.getTaskId(), event.getTableMappingId(),
                DeadLetterSourceType.CHANGE, event.getId(),
                JsonUtil.toJson(event.getPkTuple()), JsonUtil.toJson(event.getAfter()),
                ErrorCode.CHANGE_APPLY_FAILED.code(), message, digest(error),
                DeadLetterStatus.OPEN, 0);
        ctx.getDeadLetters().write(letter);
        log.error("变更转死信 changeId={} pk={} cause={}", event.getId(), event.getPkTuple(),
                message);
    }

    /**
     * 是否可重试（只重试"可恢复"的错误，配置/数据类错误直接转死信）。
     *
     * @param t 异常
     * @return true 表示可重试
     */
    static boolean isRetryable(Throwable t) {
        if (t instanceof DbSyncException) {
            return ((DbSyncException) t).isRetryable();
        }
        return false;
    }

    /**
     * 异常指纹（同因归并）。
     *
     * @param e 异常
     * @return 十六进制指纹
     */
    static String digest(Throwable e) {
        String raw = e.getClass().getName() + '|' + e.getMessage();
        return Long.toHexString(HashUtil.fnv1a64(raw));
    }

    /**
     * 空转休眠（可中断）。
     */
    private void sleep() {
        try {
            Thread.sleep(Math.max(1L, ctx.getPollInterval().toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 回滚（异常只记日志，不覆盖真实原因）。
     *
     * @param connection 连接
     */
    private static void rollback(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.debug("回滚失败（忽略）：{}", e.getMessage());
        }
    }

    /**
     * 归还连接。
     *
     * @param connection 连接
     */
    private static void close(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("归还连接失败（忽略）：{}", e.getMessage());
        }
    }

    /** @return 组号 */
    public int groupNo() {
        return groupNo;
    }
}
