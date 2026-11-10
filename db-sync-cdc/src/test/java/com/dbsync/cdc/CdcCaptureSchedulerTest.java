package com.dbsync.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.SyncTask;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 采集调度器测试：<b>周期取值、重入保护、失败退避、任务筛选</b>。
 *
 * <p><b>为什么用一个"假线程池"而不是真 sleep</b>：
 * 退避的真实效果是"两次调用之间隔了 5s/10s/30s"，若真的 sleep，一个用例要跑几十秒且不稳定。
 * 因此这里注入一个 Mockito 假 {@link ScheduledExecutorService}，它<b>记录每次安排的延迟</b>
 * 并把 Runnable 存起来由测试手动触发 —— 等价于一个可控的虚拟时钟，
 * 断言的是"调度器真正交给线程池的延迟"，比断言一个纯函数更接近真实行为。
 */
class CdcCaptureSchedulerTest extends CdcTestSupport {

    /** 记录每次调度的延迟（毫秒）。 */
    private final List<Long> scheduledDelays = new ArrayList<>();

    /** 待执行的一次性任务队列（测试手动 run）。 */
    private final Deque<Runnable> pending = new ArrayDeque<>();

    /**
     * 建一个"记录延迟 + 手动执行"的假调度线程池。
     *
     * @return 假线程池
     */
    private ScheduledExecutorService fakeScheduler() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(scheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> {
                    // 注意：不能写 Long.valueOf(invocation.getArgument(1))——
                    // Mockito 的 <T> T getArgument 会按 Long.valueOf(String) 推断成 String 再强转。
                    Long delayMs = invocation.getArgument(1);
                    Runnable task = invocation.getArgument(0);
                    scheduledDelays.add(delayMs);
                    pending.add(task);
                    return future;
                });
        return scheduler;
    }

    @Test
    @DisplayName("周期取值：任务级 poll_interval_ms 优先于全局默认")
    void should_preferTaskPollInterval() {
        CdcCaptureScheduler scheduler = new CdcCaptureScheduler(
                new ChangeCollector(syncTaskDao, tableMappingDao, List.of()), syncTaskDao,
                Duration.ofSeconds(7L), fakeScheduler());
        SyncTask task = newTask(1, 1, IncrementalMode.TRIGGER_LOG);
        syncTaskDao.transition(task.getId(), TaskState.READY, TaskState.RUNNING);
        // 任务级配 2000ms
        jdbc.update("UPDATE sync_task SET poll_interval_ms=2000 WHERE id=?", task.getId());

        assertTrue(scheduler.start(task.getId()), "RUNNING + TRIGGER_LOG 应该被登记");
        assertEquals(2_000L, scheduledDelays.get(0).longValue(),
                "第一次调度必须用任务级周期，而不是全局默认 7000");
        assertEquals(Duration.ofMillis(2_000L), scheduler.pollIntervalOf(task.getId()));
        scheduler.close();
    }

    @Test
    @DisplayName("周期取值：任务级未配置（0）时回落全局默认")
    void should_fallBackToGlobalInterval() {
        CdcCaptureScheduler scheduler = new CdcCaptureScheduler(
                new ChangeCollector(syncTaskDao, tableMappingDao, List.of()), syncTaskDao,
                Duration.ofSeconds(3L), fakeScheduler());
        SyncTask task = newTask(1, 1, IncrementalMode.TRIGGER_LOG);
        syncTaskDao.transition(task.getId(), TaskState.READY, TaskState.RUNNING);
        jdbc.update("UPDATE sync_task SET poll_interval_ms=0 WHERE id=?", task.getId());

        assertTrue(scheduler.start(task.getId()));
        assertEquals(3_000L, scheduledDelays.get(0).longValue());
        scheduler.close();
    }

    @Test
    @DisplayName("SDK_WRITE / 非 RUNNING：不登记调度（登记了也只会空转）")
    void should_notSchedule_When_captureNotNeeded() {
        CdcCaptureScheduler scheduler = new CdcCaptureScheduler(
                new ChangeCollector(syncTaskDao, tableMappingDao, List.of()), syncTaskDao,
                Duration.ofSeconds(5L), fakeScheduler());

        SyncTask sdk = newTask(1, 1, IncrementalMode.SDK_WRITE);
        syncTaskDao.transition(sdk.getId(), TaskState.READY, TaskState.RUNNING);
        assertFalse(scheduler.start(sdk.getId()), "SDK_WRITE 不需要采集调度");
        assertFalse(scheduler.isRunning(sdk.getId()));

        SyncTask ready = newTask(1, 1, IncrementalMode.TRIGGER_LOG);
        assertFalse(scheduler.start(ready.getId()), "非 RUNNING 不需要采集调度");
        assertTrue(scheduledDelays.isEmpty(), "这两种情况都不该排任何一次执行");
        scheduler.close();
    }

    @Test
    @DisplayName("退避：连续失败 3 次后，第 4 次的间隔远大于第 1 次（真实交给线程池的延迟）")
    void should_backOff_When_collectFailsRepeatedly() {
        FlakyCollector flaky = new FlakyCollector();
        CdcCaptureScheduler scheduler = new CdcCaptureScheduler(flaky, syncTaskDao,
                Duration.ofSeconds(5L), fakeScheduler());
        SyncTask task = newTask(1, 1, IncrementalMode.TRIGGER_LOG);
        syncTaskDao.transition(task.getId(), TaskState.READY, TaskState.RUNNING);
        task = syncTaskDao.findById(task.getId()).orElseThrow();
        assertTrue(scheduler.start(task.getId()));

        long firstDelay = scheduledDelays.get(0).longValue();
        // 依次触发 4 轮（每次 run 会再排下一次并记录延迟）
        for (int round = 0; round < 4; round++) {
            Runnable next = pending.poll();
            assertNotNull(next, "调度器必须始终排好下一次");
            next.run();
        }

        // scheduledDelays = [初始 5000, 失败后 5000, 失败后 10000, 失败后 30000, 失败后 60000]
        long afterFirstFailure = scheduledDelays.get(1).longValue();
        long afterThirdFailure = scheduledDelays.get(3).longValue();
        long afterFourthFailure = scheduledDelays.get(4).longValue();
        assertEquals(firstDelay, afterFirstFailure, "第 1 次失败后仍是 5s");
        assertEquals(30_000L, afterThirdFailure, "第 3 次失败后 30s");
        assertEquals(60_000L, afterFourthFailure, "第 4 次失败后 60s");
        assertTrue(afterFourthFailure > afterFirstFailure,
                "第 4 次的间隔必须大于第 1 次（退避生效）");
        assertTrue(scheduler.consecutiveFailures(task.getId()) >= 4);
        assertNotNull(scheduler.lastError(task.getId()), "失败摘要必须留痕供排障");
        scheduler.close();
    }

    @Test
    @DisplayName("成功后立即回到正常周期（不残留退避）")
    void should_resetBackoff_When_collectSucceeds() {
        FlakyCollector flaky = new FlakyCollector();
        CdcCaptureScheduler scheduler = new CdcCaptureScheduler(flaky, syncTaskDao,
                Duration.ofSeconds(5L), fakeScheduler());
        SyncTask task = newTask(1, 1, IncrementalMode.TRIGGER_LOG);
        syncTaskDao.transition(task.getId(), TaskState.READY, TaskState.RUNNING);
        assertTrue(scheduler.start(task.getId()));

        pending.poll().run();     // 失败 1 → 下次 5000
        pending.poll().run();     // 失败 2 → 下次 10000
        flaky.fail = false;
        assertEquals(10_000L, scheduledDelays.get(2).longValue());
        pending.poll().run();     // 成功 → 回到 5000
        assertEquals(5_000L, scheduledDelays.get(3).longValue(), "成功后必须回到正常周期");
        assertEquals(0, scheduler.consecutiveFailures(task.getId()));
        scheduler.close();
    }

    @Test
    @DisplayName("重入保护：上一轮没结束时，同一 taskId 的下一轮直接跳过")
    void should_skipRound_When_previousStillRunning() throws Exception {
        BlockingCollector blocking = new BlockingCollector();
        CdcCaptureScheduler scheduler = new CdcCaptureScheduler(blocking, syncTaskDao,
                Duration.ofSeconds(5L), fakeScheduler());
        SyncTask task = newTask(1, 1, IncrementalMode.TRIGGER_LOG);
        syncTaskDao.transition(task.getId(), TaskState.READY, TaskState.RUNNING);
        assertTrue(scheduler.start(task.getId()));

        java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> pending.poll().run());     // 这一轮会卡在 collectOnce 里
            assertTrue(blocking.entered.await(5L, TimeUnit.SECONDS), "第一轮应已进入采集");

            CdcCaptureScheduler.RoundResult skipped = scheduler.runRound(task.getId());
            assertTrue(skipped.skipped(), "上一轮未结束，本轮必须被跳过而不是并发执行");
            assertEquals(1, blocking.calls.get(), "并发期间 collectOnce 只能被调用 1 次");

            blocking.release.countDown();
            assertTrue(blocking.finished.await(5L, TimeUnit.SECONDS));
        } finally {
            worker.shutdownNow();
            scheduler.close();
        }
    }

    @Test
    @DisplayName("stop 之后不再排下一次（任务暂停即停采集）")
    void should_stopRescheduling_When_stopped() {
        CdcCaptureScheduler scheduler = new CdcCaptureScheduler(
                new ChangeCollector(syncTaskDao, tableMappingDao, List.of()), syncTaskDao,
                Duration.ofSeconds(5L), fakeScheduler());
        SyncTask task = newTask(1, 1, IncrementalMode.TRIGGER_LOG);
        syncTaskDao.transition(task.getId(), TaskState.READY, TaskState.RUNNING);
        assertTrue(scheduler.start(task.getId()));

        scheduler.stop(task.getId());
        assertFalse(scheduler.isRunning(task.getId()));
        int scheduledBefore = scheduledDelays.size();
        pending.poll().run();
        assertEquals(scheduledBefore, scheduledDelays.size(), "stop 后不得再排下一次");
        scheduler.close();
        assertTrue(scheduler.runningTaskIds().isEmpty());
    }

    // ---------------------------------------------------------------- 假采集者

    /**
     * 可切换成功/失败的采集者。
     */
    private static final class FlakyCollector extends ChangeCollector {

        /** 是否失败。 */
        private volatile boolean fail = true;

        /**
         * 构造（不需要 DAO，直接覆盖 collectOnce）。
         */
        private FlakyCollector() {
            super(null, null, List.of());
        }

        @Override
        public int collectOnce(long taskId) {
            if (fail) {
                throw new DbSyncException(ErrorCode.CONN_NETWORK_FAILED, "源库不可达（测试注入）");
            }
            return 1;
        }
    }

    /**
     * 阻塞式采集者（验证重入保护）。
     */
    private static final class BlockingCollector extends ChangeCollector {

        /** 已进入采集的信号。 */
        private final CountDownLatch entered = new CountDownLatch(1);

        /** 允许返回的信号。 */
        private final CountDownLatch release = new CountDownLatch(1);

        /** 已结束的信号。 */
        private final CountDownLatch finished = new CountDownLatch(1);

        /** 调用次数。 */
        private final AtomicInteger calls = new AtomicInteger();

        /** 捕获异常（应无）。 */
        private final AtomicReference<RuntimeException> error = new AtomicReference<>();

        /**
         * 构造。
         */
        private BlockingCollector() {
            super(null, null, List.of());
        }

        @Override
        public int collectOnce(long taskId) {
            calls.incrementAndGet();
            entered.countDown();
            try {
                release.await(5L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                error.set(new IllegalStateException(e));
            }
            finished.countDown();
            return 1;
        }
    }
}
