package com.dbsync.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.enums.IncrementalMode;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 采集退避与"是否需要采集"的纯策略测试。
 *
 * <p>为什么值得单独测一个"返回 Duration 的函数"：它直接决定<b>源库压力</b>。
 * 写错方向（比如把阶梯写成递减、或忘了封顶）不会有任何异常，
 * 只会在真实故障时把源库压死或者让退避退到几小时——这两件事都不会被功能测试发现。
 */
class CaptureBackoffTest {

    @Test
    @DisplayName("退避阶梯：5s → 10s → 30s → 60s → 120s → 封顶 5min，且严格递增")
    void should_growAndCap_When_failuresAccumulate() {
        assertEquals(Duration.ofSeconds(5L), CaptureBackoff.delayAfter(1));
        assertEquals(Duration.ofSeconds(10L), CaptureBackoff.delayAfter(2));
        assertEquals(Duration.ofSeconds(30L), CaptureBackoff.delayAfter(3));
        assertEquals(Duration.ofSeconds(60L), CaptureBackoff.delayAfter(4));
        assertEquals(Duration.ofSeconds(120L), CaptureBackoff.delayAfter(5));
        assertEquals(CaptureBackoff.CAP, CaptureBackoff.delayAfter(6));
        assertEquals(CaptureBackoff.CAP, CaptureBackoff.delayAfter(1_000),
                "再多失败也不能超过 5 分钟上限");

        Duration previous = Duration.ZERO;
        for (int failures = 1; failures <= 20; failures++) {
            Duration delay = CaptureBackoff.delayAfter(failures);
            assertTrue(delay.compareTo(previous) >= 0, "退避必须单调不减：" + failures);
            assertTrue(delay.compareTo(CaptureBackoff.CAP) <= 0, "退避必须封顶：" + failures);
            previous = delay;
        }
        assertTrue(CaptureBackoff.delayAfter(4).compareTo(CaptureBackoff.delayAfter(1)) > 0,
                "第 4 次失败的等待必须大于第 1 次");
    }

    @Test
    @DisplayName("未失败时也不返回 0（避免调用方忙等）")
    void should_neverReturnZero() {
        assertTrue(CaptureBackoff.delayAfter(0).toMillis() > 0L);
        assertTrue(CaptureBackoff.delayAfter(-5).toMillis() > 0L);
    }

    @Test
    @DisplayName("日志采样：第 1 次与每 10 次打 WARN，其余降级")
    void should_sampleWarnings() {
        assertTrue(CaptureBackoff.shouldWarn(1, 10), "第一次失败必须可见");
        assertFalse(CaptureBackoff.shouldWarn(2, 10));
        assertFalse(CaptureBackoff.shouldWarn(9, 10));
        assertTrue(CaptureBackoff.shouldWarn(10, 10));
        assertTrue(CaptureBackoff.shouldWarn(20, 10));
        assertFalse(CaptureBackoff.shouldWarn(5, 0), "采样间隔非法时按 10 处理 → 第 5 次不采样");
    }

    @Test
    @DisplayName("需要采集的模式只有 TRIGGER_LOG / TIMESTAMP_VERSION；SDK_WRITE 与 null 不采集")
    void should_needCaptureOnlyForServerSideModes() {
        assertTrue(CaptureBackoff.needsCapture(IncrementalMode.TRIGGER_LOG));
        assertTrue(CaptureBackoff.needsCapture(IncrementalMode.TIMESTAMP_VERSION));
        assertFalse(CaptureBackoff.needsCapture(IncrementalMode.SDK_WRITE),
                "SDK_WRITE 由业务侧双写进 change_log，平台再轮询会把同一份变更记两遍");
        assertFalse(CaptureBackoff.needsCapture(null), "没有模式就没有采集器能负责它");
    }
}
