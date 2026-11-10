package com.dbsync.api.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.api.dto.cdc.CdcRuntimeView;
import com.dbsync.cdc.ChangeConsumer;
import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.core.pool.NamedThreadPoolManager;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 运行期管理器测试（CDC-02 的启动/停止语义）。
 *
 * <p>用<b>真实</b> {@link ChangeConsumer} + 真实 {@link CdcContextFactory} 装配出来的上下文
 * （H2 元数据库 + H2 目标库），只在工厂外面套一层计数器/故障注入：
 * 这样"只装配一次""失败留痕"断言的是真实会话行为，而不是 mock 的调用次数。
 */
class CdcRuntimeManagerTest extends CdcRuntimeTestSupport {

    @Test
    @DisplayName("start 幂等：连调两次只装配一次上下文，会话只起一个")
    void should_startOnce_When_startTwice() {
        long taskId = runnableTask("t_start_once");
        AtomicInteger created = new AtomicInteger();
        NamedThreadPoolManager testPools = new NamedThreadPoolManager();
        CdcRuntimeManager manager = new CdcRuntimeManager(testPools, counting(created), taskDao,
                INSTANCE);
        try {
            manager.start(taskId);
            assertTrue(manager.isRunning(taskId), "第一次 start 后会话应在跑");
            manager.start(taskId);
            manager.start(taskId);
            assertEquals(1, created.get(), "重复 start 不应重新装配上下文");
            assertEquals(1, manager.runningTaskIds().size(), "只应有一个运行中的任务");
            assertEquals(Long.valueOf(taskId), manager.runningTaskIds().get(0));
            CdcRuntimeView view = manager.status(taskId);
            assertTrue(view.running());
            assertNotNull(view.startedAt(), "运行中必须有启动时间");
            assertEquals(INSTANCE, view.ownerInstance());
            assertNull(view.lastError());
            assertTrue(view.consumerConcurrency() >= 1, "并发度来自任务配置");
        } finally {
            manager.stopAll();
            testPools.shutdownAll(Duration.ofMillis(500L));
        }
    }

    @Test
    @DisplayName("stop 幂等：重复 stop 不抛异常，且会话确实停掉")
    void should_beIdempotent_When_stopTwice() {
        long taskId = runnableTask("t_stop_twice");
        runtime.start(taskId);
        assertTrue(runtime.isRunning(taskId));

        runtime.stop(taskId);
        assertFalse(runtime.isRunning(taskId), "stop 后不应再算运行中");
        assertNull(runtime.status(taskId).startedAt(), "停止后启动时间应清空");

        runtime.stop(taskId);
        runtime.stop(taskId);
        assertFalse(runtime.isRunning(taskId));
        assertTrue(runtime.runningTaskIds().isEmpty());
    }

    @Test
    @DisplayName("stopAll：清空全部会话")
    void should_stopAllSessions() {
        long first = runnableTask("t_all_1");
        long second = runnableTask("t_all_2");
        runtime.start(first);
        runtime.start(second);
        assertEquals(2, runtime.runningTaskIds().size());

        runtime.stopAll();

        assertTrue(runtime.runningTaskIds().isEmpty(), "stopAll 后不应还有会话");
        assertFalse(runtime.isRunning(first));
        assertFalse(runtime.isRunning(second));
    }

    @Test
    @DisplayName("start 抛异常：记录 lastError、不抛出、不影响再次 start")
    void should_recordLastError_When_startFails() {
        long taskId = runnableTask("t_start_fail");
        AtomicInteger calls = new AtomicInteger();
        ChangeConsumer.ContextFactory flaky = (id, stopSignal) -> {
            if (calls.incrementAndGet() == 1) {
                throw new DbSyncException(ErrorCode.CONN_NETWORK_FAILED, "目标库不可达（测试注入）");
            }
            return factory.create(id, stopSignal);
        };
        NamedThreadPoolManager testPools = new NamedThreadPoolManager();
        CdcRuntimeManager manager = new CdcRuntimeManager(testPools, flaky, taskDao, INSTANCE);
        try {
            manager.start(taskId);
            assertFalse(manager.isRunning(taskId), "装配失败不应留下运行中的会话");
            String lastError = manager.status(taskId).lastError();
            assertNotNull(lastError, "失败必须留痕在 lastError");
            assertTrue(lastError.contains("目标库不可达"), "lastError 应带失败原因，实际：" + lastError);
            assertNull(manager.status(taskId).startedAt());

            manager.start(taskId);
            assertTrue(manager.isRunning(taskId), "故障恢复后再次 start 应成功");
            assertNull(manager.status(taskId).lastError(), "成功后应清空 lastError");
        } finally {
            manager.stopAll();
            testPools.shutdownAll(Duration.ofMillis(500L));
        }
    }

    @Test
    @DisplayName("增量模式判定：null 不需要消费者，其余模式都需要")
    void should_needConsumer_exceptNull() {
        assertFalse(CdcRuntimeManager.needsConsumer(null));
        assertTrue(CdcRuntimeManager.needsConsumer(IncrementalMode.TRIGGER_LOG));
        assertTrue(CdcRuntimeManager.needsConsumer(IncrementalMode.TIMESTAMP_VERSION));
        assertTrue(CdcRuntimeManager.needsConsumer(IncrementalMode.SDK_WRITE));
    }

    @Test
    @DisplayName("生命周期对齐：start 同时拉起采集调度，stop 先停采集再停消费")
    void should_startAndStopCapture_WithConsumer() {
        long taskId = runnableTask("t_capture_lifecycle");
        java.util.concurrent.atomic.AtomicInteger polls = new java.util.concurrent.atomic.AtomicInteger();
        com.dbsync.cdc.ChangeCollector collector = new com.dbsync.cdc.ChangeCollector(taskDao,
                mappingDao, java.util.List.of(new com.dbsync.cdc.ChangePoller() {
                    @Override
                    public int pollOnce(long id, long mappingId) {
                        return polls.incrementAndGet();
                    }

                    @Override
                    public IncrementalMode mode() {
                        return IncrementalMode.TRIGGER_LOG;
                    }
                }));
        // 采集周期用 1 小时：本用例只验证"登记/撤销"，不需要它真的轮询
        com.dbsync.cdc.CdcCaptureScheduler capture = new com.dbsync.cdc.CdcCaptureScheduler(
                collector, taskDao, Duration.ofHours(1L));
        NamedThreadPoolManager testPools = new NamedThreadPoolManager();
        CdcRuntimeManager manager = new CdcRuntimeManager(testPools, factory, taskDao, INSTANCE,
                watermarkDao, capture);
        try {
            manager.start(taskId);
            assertTrue(manager.isRunning(taskId), "消费会话应在跑");
            assertTrue(manager.isCapturing(taskId), "采集调度也必须同源启动");
            assertEquals(1, manager.runningTaskIds().size(), "两个半边属于同一任务，不应重复");

            manager.stop(taskId);
            assertFalse(manager.isRunning(taskId), "消费会话应已停止");
            assertFalse(manager.isCapturing(taskId), "采集调度必须一起停（否则暂停后队列还在长）");
            assertTrue(manager.runningTaskIds().isEmpty());
        } finally {
            manager.stopAll();
            manager.shutdown();
            testPools.shutdownAll(Duration.ofMillis(500L));
        }
    }

    @Test
    @DisplayName("SDK_WRITE：起消费但不起采集（该模式不需要去源库轮询）")
    void should_notStartCapture_When_modeIsSdkWrite() {
        long taskId = newTask(TaskState.RUNNING, IncrementalMode.SDK_WRITE, 1, 1);
        newMapping(taskId, "t_sdk_lifecycle");
        createTargetTable("t_sdk_lifecycle");
        com.dbsync.cdc.CdcCaptureScheduler capture = new com.dbsync.cdc.CdcCaptureScheduler(
                new com.dbsync.cdc.ChangeCollector(taskDao, mappingDao, java.util.List.of()),
                taskDao, Duration.ofHours(1L));
        NamedThreadPoolManager testPools = new NamedThreadPoolManager();
        CdcRuntimeManager manager = new CdcRuntimeManager(testPools, factory, taskDao, INSTANCE,
                watermarkDao, capture);
        try {
            manager.start(taskId);
            assertTrue(manager.isRunning(taskId), "SDK_WRITE 仍然需要消费者（有变更要应用）");
            assertFalse(manager.isCapturing(taskId), "SDK_WRITE 不需要采集（业务侧已双写）");
        } finally {
            manager.stopAll();
            manager.shutdown();
            testPools.shutdownAll(Duration.ofMillis(500L));
        }
    }

    // ---------------------------------------------------------------- 辅助

    /**
     * 造一个"可消费"的任务：RUNNING + 1 个启用映射 + 目标表存在。
     *
     * @param table 目标表名
     * @return 任务 ID
     */
    private long runnableTask(String table) {
        long taskId = newTask(TaskState.RUNNING, IncrementalMode.TRIGGER_LOG, 2, 1);
        newMapping(taskId, table);
        createTargetTable(table);
        return taskId;
    }

    /**
     * 在工厂外面套一层计数器。
     *
     * @param created 计数
     * @return 工厂
     */
    private ChangeConsumer.ContextFactory counting(AtomicInteger created) {
        return (taskId, stopSignal) -> {
            created.incrementAndGet();
            return factory.create(taskId, stopSignal);
        };
    }
}
