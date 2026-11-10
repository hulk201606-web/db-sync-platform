package com.dbsync.cdc;

import com.dbsync.common.enums.IncrementalMode;

/**
 * 采集器统一端口（一个任务的一张表拉一批源库变更 → 入队 {@code sync_change_log}）。
 *
 * <p><b>为什么抽这个接口</b>：采集侧此前有两个互不相干的实现
 * （{@link TriggerLogPoller} / {@link TimestampPoller}），而调度方
 * （{@link CdcCaptureScheduler}）若直接 {@code if (mode == TRIGGER_LOG) ... else ...}，
 * 将来加第四种模式就要改调度器。抽成端口后，调度器只认"谁负责这种模式"，
 * 单测也能用假实现精确统计"某模式到底有没有被轮询"（SDK_WRITE 必须为 0 次）。
 *
 * <p><b>模式与实现的对应关系是排他的</b>：{@link #mode()} 返回哪个模式，
 * 就只会被那个模式的任务调用，不存在"触发器轮询器被时间戳任务调用"的越界。
 */
public interface ChangePoller {

    /**
     * 拉取一批并入队。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @return 本次入队条数（&gt;= 0）
     */
    int pollOnce(long taskId, long tableMappingId);

    /**
     * 本实现负责的增量模式。
     *
     * @return 模式
     */
    IncrementalMode mode();
}
