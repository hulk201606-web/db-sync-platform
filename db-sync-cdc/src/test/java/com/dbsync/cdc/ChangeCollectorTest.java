package com.dbsync.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 采集编排测试：<b>什么条件下才允许去轮询源库</b>。
 *
 * <p>三个"必须跳过"的场景都是上线风险点，而不是优化项：
 * <ul>
 *   <li><b>SDK_WRITE 不能采集</b>：该模式由业务侧在业务事务内双写 {@code sync_change_log}，
 *       平台再轮询源库会把同一份变更记两遍（队列翻倍、水位错乱）；</li>
 *   <li><b>非 RUNNING 不能采集</b>：PAUSED/CANCELLED 的任务还在往队列灌数据，
 *       等于"暂停了还在动数据"；</li>
 *   <li><b>模式不能越界</b>：{@code TRIGGER_LOG} 任务只能由触发器轮询器处理，
 *       {@code TIMESTAMP_VERSION} 只能由时间戳轮询器处理，否则同一变更会被两种口径各采一次。</li>
 * </ul>
 */
class ChangeCollectorTest extends CdcTestSupport {

    private CountingPoller triggerPoller;
    private CountingPoller timestampPoller;
    private CountingPoller sdkPoller;

    /**
     * 装配被测对象（用计数假实现替代真轮询器，这样"有没有被调用"是精确断言）。
     */
    private ChangeCollector collector() {
        triggerPoller = new CountingPoller(IncrementalMode.TRIGGER_LOG);
        timestampPoller = new CountingPoller(IncrementalMode.TIMESTAMP_VERSION);
        sdkPoller = new CountingPoller(IncrementalMode.SDK_WRITE);
        return new ChangeCollector(syncTaskDao, tableMappingDao,
                List.of(triggerPoller, timestampPoller, sdkPoller));
    }

    @Test
    @DisplayName("SDK_WRITE：不采集（否则平台轮询 + 业务双写 = 同一变更入队两次）")
    void should_skipCapture_When_modeIsSdkWrite() {
        ChangeCollector collector = collector();
        SyncTask task = newTask(2, 1, IncrementalMode.SDK_WRITE);
        setRunning(task.getId());
        newMapping(task.getId(), "t_sdk");

        assertEquals(0, collector.collectOnce(task.getId()), "SDK_WRITE 不应产生任何采集");
        assertEquals(0, sdkPoller.calls.get(), "SDK_WRITE 的采集器必须一次都没被调用");
        assertEquals(0, triggerPoller.calls.get(), "也不能顺手用触发器轮询器代劳");
        assertEquals(0, timestampPoller.calls.get());
    }

    @Test
    @DisplayName("非 RUNNING 任务：不采集")
    void should_skipCapture_When_taskNotRunning() {
        ChangeCollector collector = collector();
        SyncTask task = newTask(2, 1, IncrementalMode.TRIGGER_LOG);
        // newTask 造的是 READY
        newMapping(task.getId(), "t_ready");

        assertEquals(0, collector.collectOnce(task.getId()));
        assertEquals(0, triggerPoller.calls.get(), "READY 任务不应触发任何源库轮询");
    }

    @Test
    @DisplayName("TRIGGER_LOG：只走触发器轮询器（不越界到时间戳轮询器）")
    void should_useTriggerPollerOnly_When_modeIsTriggerLog() {
        ChangeCollector collector = collector();
        SyncTask task = newTask(2, 1, IncrementalMode.TRIGGER_LOG);
        setRunning(task.getId());
        TableMapping first = newMapping(task.getId(), "t_trg_1");
        TableMapping second = newMapping(task.getId(), "t_trg_2");

        assertEquals(2, collector.collectOnce(task.getId()), "两张启用映射各返回 1 条 → 合计 2");
        assertEquals(2, triggerPoller.calls.get(), "每张启用映射都要采一轮");
        assertEquals(0, timestampPoller.calls.get(), "模式必须排他，不许越界");
        assertTrue(triggerPoller.mappings.contains(Long.valueOf(first.getId())));
        assertTrue(triggerPoller.mappings.contains(Long.valueOf(second.getId())));
    }

    @Test
    @DisplayName("TIMESTAMP_VERSION：只走时间戳轮询器")
    void should_useTimestampPollerOnly_When_modeIsTimestamp() {
        ChangeCollector collector = collector();
        SyncTask task = newTask(2, 1, IncrementalMode.TIMESTAMP_VERSION);
        setRunning(task.getId());
        newMapping(task.getId(), "t_ts_mode");

        assertEquals(1, collector.collectOnce(task.getId()));
        assertEquals(1, timestampPoller.calls.get());
        assertEquals(0, triggerPoller.calls.get(), "模式必须排他，不许越界");
    }

    @Test
    @DisplayName("没有启用的映射：不采集（无事可做）")
    void should_skipCapture_When_noEnabledMapping() {
        ChangeCollector collector = collector();
        SyncTask task = newTask(2, 1, IncrementalMode.TRIGGER_LOG);
        setRunning(task.getId());
        TableMapping disabled = newMapping(task.getId(), "t_disabled");
        tableMappingDao.setEnabled(disabled.getId(), false);

        assertEquals(0, collector.collectOnce(task.getId()));
        assertEquals(0, triggerPoller.calls.get());
    }

    @Test
    @DisplayName("单表失败：其余表照常采集，最终抛 E4003 触发退避")
    void should_collectOthersAndFail_When_oneMappingFails() {
        ChangeCollector collector = collector();
        SyncTask task = newTask(2, 1, IncrementalMode.TRIGGER_LOG);
        setRunning(task.getId());
        TableMapping ok = newMapping(task.getId(), "t_ok");
        TableMapping bad = newMapping(task.getId(), "t_bad");
        triggerPoller.failingMapping = Long.valueOf(bad.getId());

        DbSyncException e = assertThrows(DbSyncException.class,
                () -> collector.collectOnce(task.getId()));
        assertEquals("E4003", e.errorCode().code());
        assertTrue(triggerPoller.calls.get() >= 2, "坏表不能拦住好表");
        assertTrue(triggerPoller.mappings.contains(Long.valueOf(ok.getId())));
    }

    @Test
    @DisplayName("构造校验：同一模式注册两个采集器直接失败（不许两个真相）")
    void should_rejectDuplicateModeRegistration() {
        assertThrows(IllegalArgumentException.class, () -> new ChangeCollector(syncTaskDao,
                tableMappingDao,
                List.of(new CountingPoller(IncrementalMode.TRIGGER_LOG),
                        new CountingPoller(IncrementalMode.TRIGGER_LOG))));
    }

    // ---------------------------------------------------------------- 辅助

    /**
     * 把任务置为 RUNNING（直接写库，模拟引擎的真实跃迁结果）。
     *
     * @param taskId 任务 ID
     */
    private void setRunning(long taskId) {
        syncTaskDao.transition(taskId, TaskState.READY, TaskState.RUNNING);
        assertEquals(TaskState.RUNNING, syncTaskDao.stateOf(taskId).orElseThrow());
    }

    /**
     * 计数采集器（记录调用次数与被采的映射）。
     */
    private static final class CountingPoller implements ChangePoller {

        /** 负责的模式。 */
        private final IncrementalMode mode;

        /** 调用次数。 */
        private final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();

        /** 被采过的映射 ID。 */
        private final List<Long> mappings = new ArrayList<>();

        /** 需要抛异常的映射 ID（模拟单表故障）。 */
        private volatile Long failingMapping;

        /**
         * @param mode 模式
         */
        private CountingPoller(IncrementalMode mode) {
            this.mode = mode;
        }

        @Override
        public int pollOnce(long taskId, long tableMappingId) {
            calls.incrementAndGet();
            mappings.add(Long.valueOf(tableMappingId));
            if (failingMapping != null && failingMapping.longValue() == tableMappingId) {
                throw new DbSyncException(
                        com.dbsync.common.exception.ErrorCode.TRIGGER_DEPLOY_FAILED,
                        "源库日志表不可读（测试注入）");
            }
            return 1;
        }

        @Override
        public IncrementalMode mode() {
            return mode;
        }
    }
}
