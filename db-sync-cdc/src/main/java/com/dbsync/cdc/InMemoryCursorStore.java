package com.dbsync.cdc;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进程内位点存储（<b>仅单测 / 无元数据库场景</b>，生产必须用
 * {@link CheckpointCursorStore}）。
 *
 * <p>存在它的唯一理由：{@code db-sync-cdc} 里大量轮询器单测只关心"解析/分页/去重"，
 * 不想为了造一个 H2 元数据库而引入 Flyway。把这些测试的位点语义显式化为一个类，
 * 比让轮询器内部偷偷保留一个 {@code ConcurrentHashMap} 更容易看清"谁在持久化"。
 *
 * <p><b>重启语义</b>：本类随进程消亡，因此用它就意味着"重启后从位点 0 重放"。
 * 这个代价在单测里无所谓，在生产里不可接受——所以生产装配
 * （{@code CdcRuntimeConfig}）只注入 {@link CheckpointCursorStore}，
 * 且 {@code CdcCaptureScheduler} 的构造函数不接受 null。
 */
public class InMemoryCursorStore implements PollerCursorStore {

    private final Map<String, State> states = new ConcurrentHashMap<>();

    /**
     * 读位点。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @return 位点
     */
    @Override
    public Cursor load(long taskId, long tableMappingId) {
        State state = states.get(key(taskId, tableMappingId));
        if (state == null) {
            return Cursor.empty();
        }
        return new Cursor(state.lastLogId, state.lastCommitTs, state.version.get());
    }

    /**
     * 认领（本实现恒成功）。
     *
     * <p>进程内不存在第二个实例与本实现竞争，因此"认领"没有意义；
     * 真正的单实例内重入保护在 {@link CdcCaptureScheduler}（同一个 taskId 的上一轮
     * 未结束时下一轮直接跳过）。生产的多实例竞争由 {@link CheckpointCursorStore} 处理。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @param window         采集权窗口（本实现忽略）
     * @return 恒 true
     */
    @Override
    public boolean tryClaim(long taskId, long tableMappingId, java.time.Duration window) {
        states.computeIfAbsent(key(taskId, tableMappingId), k -> new State());
        return true;
    }

    /**
     * 前进位点。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @param lastLogId      日志位点（null 表示不变）
     * @param lastCommitTs   时间位点（null 表示不变）
     */
    @Override
    public void advance(long taskId, long tableMappingId, Long lastLogId, Instant lastCommitTs) {
        State state = states.computeIfAbsent(key(taskId, tableMappingId), k -> new State());
        if (lastLogId != null) {
            state.lastLogId = Math.max(state.lastLogId, lastLogId.longValue());
        }
        if (lastCommitTs != null
                && (state.lastCommitTs == null || lastCommitTs.isAfter(state.lastCommitTs))) {
            state.lastCommitTs = lastCommitTs;
        }
        state.version.incrementAndGet();
    }

    /**
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @return 复合键
     */
    private static String key(long taskId, long tableMappingId) {
        return taskId + ":" + tableMappingId;
    }

    /** 单个映射的位点状态。 */
    private static final class State {

        /** 日志位点。 */
        private volatile long lastLogId;

        /** 时间位点。 */
        private volatile Instant lastCommitTs;

        /** 版本（前进次数，诊断用）。 */
        private final AtomicInteger version = new AtomicInteger(0);
    }
}
