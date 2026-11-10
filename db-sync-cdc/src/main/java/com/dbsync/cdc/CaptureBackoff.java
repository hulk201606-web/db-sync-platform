package com.dbsync.cdc;

import com.dbsync.common.enums.IncrementalMode;
import java.time.Duration;

/**
 * 采集失败退避策略（<b>纯函数</b>，便于单测直接断言数值而不依赖时钟）。
 *
 * <p><b>为什么需要退避</b>：采集是"每 N 秒问一次源库"的主动行为。源库不可用（网络抖动、
 * 主库切换、表被锁）时，固定 5s 周期会在半小时内问 360 次，把本来只是"慢"的源库
 * 压成"挂"。指数退避把它收敛成 6 次。
 *
 * <p><b>为什么不是纯 2 的幂</b>：{@code 5s→10s→20s→40s} 在第三次失败时仍嫌激进
 * （连不上源库的前 1 分钟通常就是这个阶段），而 {@code 5s→10s→30s} 更贴合
 * 常见故障的恢复曲线；再往上一律按 {@link #CAP} 封顶，避免"退避到 1 小时"
 * 让恢复后的追平变慢。
 */
public final class CaptureBackoff {

    /** 退避阶梯（第 n 次连续失败后的等待时长）。 */
    private static final Duration[] LADDER = {
        Duration.ofSeconds(5L),
        Duration.ofSeconds(10L),
        Duration.ofSeconds(30L),
        Duration.ofSeconds(60L),
        Duration.ofSeconds(120L)
    };

    /** 退避上限（5 分钟）。 */
    public static final Duration CAP = Duration.ofMinutes(5L);

    /**
     * 私有构造（工具类）。
     */
    private CaptureBackoff() {
    }

    /**
     * 第 {@code consecutiveFailures} 次连续失败后应等待多久。
     *
     * <p>{@code <=0} 视为"还没失败"，返回第一档（{@link #LADDER} 首项），
     * 避免调用方传 0 时得到 0ms 造成忙等。
     *
     * @param consecutiveFailures 连续失败次数（1 = 第一次失败）
     * @return 等待时长（永不为 0，永不超过 {@link #CAP}）
     */
    public static Duration delayAfter(int consecutiveFailures) {
        int index = consecutiveFailures <= 1 ? 0 : Math.min(consecutiveFailures - 1,
                LADDER.length);
        Duration delay = index >= LADDER.length ? CAP : LADDER[index];
        return delay.compareTo(CAP) > 0 ? CAP : delay;
    }

    /**
     * 是否该打一条 {@code log.warn}（日志采样：每 10 次失败一条，外加第一次）。
     *
     * <p>没有采样的话，一个整夜不可用的源库会刷出上万条同质 WARN，
     * 真正需要被看到的错误会被淹掉。
     *
     * @param consecutiveFailures 连续失败次数
     * @param sampleEvery         采样间隔（{@code <=0} 时按 10 处理）
     * @return true 表示本轮应打 WARN
     */
    public static boolean shouldWarn(int consecutiveFailures, int sampleEvery) {
        int every = sampleEvery <= 0 ? 10 : sampleEvery;
        return consecutiveFailures <= 1 || consecutiveFailures % every == 0;
    }

    /**
     * 目标增量模式是否需要"从源库采集"。
     *
     * <p><b>排他表</b>（本平台的三种模式）：
     * <ul>
     *   <li>{@code TRIGGER_LOG} → 需要，由 {@code TriggerLogPoller} 负责；</li>
     *   <li>{@code TIMESTAMP_VERSION} → 需要，由 {@code TimestampPoller} 负责；</li>
     *   <li>{@code SDK_WRITE} → <b>不需要</b>：变更由业务侧在业务事务内双写进
     *       {@code sync_change_log}（见 {@code DbSyncWriter}），平台再轮询源库
     *       就是重复采集——同一份变更会入队两次；</li>
     *   <li>{@code null} / 未知模式 → 不需要（没有一种采集器能负责它）。</li>
     * </ul>
     *
     * <p>注意与 {@code CdcRuntimeManager#needsConsumer} 的区别：SDK_WRITE
     * <b>需要消费者</b>（有变更要应用）但<b>不需要采集</b>。两者是不同的判断，
     * 混成一个方法会让 SDK_WRITE 静默停止消费。
     *
     * @param mode 增量模式（可 null）
     * @return true 表示需要采集
     */
    public static boolean needsCapture(IncrementalMode mode) {
        if (mode == null) {
            return false;
        }
        switch (mode) {
            case TRIGGER_LOG:
            case TIMESTAMP_VERSION:
                return true;
            case SDK_WRITE:
            default:
                return false;
        }
    }
}
