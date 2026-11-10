package com.dbsync.cdc;

import java.time.Instant;

/**
 * 采集位点（{@code TRIGGER_LOG} 的 {@code last_log_id} / {@code TIMESTAMP_VERSION} 的
 * {@code last_commit_ts}）的持久化端口。
 *
 * <p><b>为什么必须有这个端口</b>：本轮之前两个轮询器把位点存在进程内
 * {@code ConcurrentHashMap} 里，进程一重启就从日志表起点重放。触发器模式下
 * "重放"意味着源库大表被反复扫、{@code sync_change_log} 被反复写，
 * 代价随表大小线性上升；时间戳模式下更糟——重启后从"当前时间 - 5s"开始，
 * <b>停机窗口内的变更直接漏掉</b>。两种都不可接受，所以位点必须落库。
 *
 * <p><b>落在哪张表</b>：{@code sync_checkpoint}，{@code scope_type='POLLER'}，
 * {@code scope_no=0}，{@code table_mapping_id=<映射 ID>}（唯一约束
 * {@code uk_ckpt(task_id, table_mapping_id, scope_type, scope_no)} 保证一表一行）。
 * 触发器模式写 {@code last_log_id}，时间戳模式写 {@code last_commit_ts}；
 * 两个字段 V1 就有，不需要新增列。
 *
 * <p><b>重启恢复</b>：轮询器每轮开头 {@link #load} 位点，读到什么就从什么位置继续——
 * 不需要"启动时预热"，也就不会出现"预热漏了某个映射"这类问题。
 *
 * <p><b>多实例</b>：{@link #tryClaim} 用位点行的乐观版本做"本轮采集权"CAS，
 * 只有一个实例能拿到；详见 {@code CheckpointDao#bumpVersion}。
 */
public interface PollerCursorStore {

    /**
     * 位点快照（不可变）。
     *
     * @param lastLogId    触发器模式：已处理到的最大 {@code log_id}（无位点为 0）
     * @param lastCommitTs 时间戳模式：已处理到的最大 {@code commit_ts}（无位点为 null）
     * @param version      乐观版本（用于认领与保存；行不存在时为 -1）
     */
    record Cursor(long lastLogId, Instant lastCommitTs, int version) {

        /**
         * 规范化构造（防止出现 {@code lastLogId < 0} 这种把查询条件写成负数的位点）。
         *
         * @param lastLogId    日志位点
         * @param lastCommitTs 时间位点
         * @param version      版本
         */
        public Cursor {
            if (lastLogId < 0L) {
                lastLogId = 0L;
            }
        }

        /** @return 空位点（从未采集过） */
        public static Cursor empty() {
            return new Cursor(0L, null, -1);
        }
    }

    /**
     * 读位点。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @return 位点（无记录时返回 {@link Cursor#empty()}，<b>不</b>返回 null）
     */
    Cursor load(long taskId, long tableMappingId);

    /**
     * 认领本轮采集权（多实例下同一个窗口内只有一个实例返回 true）。
     *
     * <p>失败不是错误——说明别的实例正在采集这一批，本轮直接跳过即可。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @param window         采集权窗口（应取该任务的轮询周期；null 表示用实现默认值）
     * @return true 表示本轮由本实例采集
     */
    boolean tryClaim(long taskId, long tableMappingId, java.time.Duration window);

    /**
     * 前进位点（在"变更已写入 {@code sync_change_log} 之后"调用）。
     *
     * <p>顺序不能颠倒：先写变更再前进位点，最坏是"重复采集一次"（应用层 upsert 幂等）；
     * 反过来则可能"位点说采过了、变更却没入队"，那是<b>永久丢失</b>。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @param lastLogId      触发器模式的新位点（时间戳模式传 null 表示不更新该列）
     * @param lastCommitTs   时间戳模式的新位点（触发器模式传 null）
     */
    void advance(long taskId, long tableMappingId, Long lastLogId, Instant lastCommitTs);
}
