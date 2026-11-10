package com.dbsync.api.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.dbsync.api.dto.cdc.CdcModeRequest;
import com.dbsync.api.service.CdcService;
import com.dbsync.api.service.MigrationService;
import com.dbsync.api.service.TaskService;
import com.dbsync.cdc.TriggerInstaller;
import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.core.port.IncrementalMetricsPort;
import com.dbsync.migration.MigrationEngine;
import com.dbsync.migration.MigrationPrecheck;
import com.dbsync.migration.RowCountVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 生命周期接线测试（CDC-02：状态一变就要启停消费）。
 *
 * <p>迁移引擎用 mock，但<b>状态跃迁是真的写库</b>（mock 的桩会把 {@code sync_task.state} 改成
 * 引擎真实会写的值）：这样断言到的是"以库状态为准"的接线逻辑，而不是"有没有调用某个方法"。
 */
class CdcLifecycleTest extends CdcRuntimeTestSupport {

    private MigrationEngine engine;
    private TaskService taskService;
    private MigrationService migrationService;
    private CdcService cdcService;

    /**
     * 装配被测服务（引擎/预检/核数用 mock，其余全真）。
     */
    @BeforeEach
    void setUpServices() {
        engine = mock(MigrationEngine.class);
        taskService = new TaskService(taskDao, dataSourceDao, mappingDao, idGenerator, runtime);
        migrationService = new MigrationService(engine, mock(MigrationPrecheck.class),
                mock(RowCountVerifier.class), taskService, runtime);
        cdcService = new CdcService(taskDao, mappingDao, changeLogDao, mock(TriggerInstaller.class),
                mock(IncrementalMetricsPort.class), taskService, runtime);
    }

    @Test
    @DisplayName("任务进入 RUNNING：增量消费被拉起")
    void should_startConsumer_When_taskBecomesRunning() {
        long taskId = preparedTask(TaskState.DRAFT);
        doAnswer(invocation -> {
            setState(taskId, TaskState.RUNNING);
            return null;
        }).when(engine).start(taskId);

        migrationService.start(taskId);

        assertEquals(TaskState.RUNNING, taskDao.stateOf(taskId).orElseThrow());
        assertTrue(runtime.isRunning(taskId), "RUNNING 任务必须有消费会话");
        assertTrue(runtime.status(taskId).running());
    }

    @Test
    @DisplayName("任务 PAUSE：增量消费停止")
    void should_stopConsumer_When_taskPaused() {
        long taskId = preparedTask(TaskState.RUNNING);
        runtime.start(taskId);
        assertTrue(runtime.isRunning(taskId));
        doAnswer(invocation -> {
            setState(taskId, TaskState.PAUSED);
            return null;
        }).when(engine).pause(taskId);

        migrationService.pause(taskId);

        assertEquals(TaskState.PAUSED, taskDao.stateOf(taskId).orElseThrow());
        assertFalse(runtime.isRunning(taskId), "PAUSED 任务不应继续消费");
    }

    @Test
    @DisplayName("任务 CANCEL（→FAILED）：增量消费停止")
    void should_stopConsumer_When_taskCancelled() {
        long taskId = preparedTask(TaskState.RUNNING);
        runtime.start(taskId);
        assertTrue(runtime.isRunning(taskId));
        doAnswer(invocation -> {
            setState(taskId, TaskState.FAILED);
            return null;
        }).when(engine).cancel(taskId);

        migrationService.cancel(taskId);

        assertEquals(TaskState.FAILED, taskDao.stateOf(taskId).orElseThrow());
        assertFalse(runtime.isRunning(taskId), "FAILED 任务不应继续消费");
    }

    @Test
    @DisplayName("任务 ARCHIVE：增量消费停止")
    void should_stopConsumer_When_taskArchived() {
        // 状态机里只有 PAUSED/FINISHED 能归档，所以造"已暂停但会话残留"的形态
        long taskId = preparedTask(TaskState.PAUSED);
        runtime.start(taskId);
        assertTrue(runtime.isRunning(taskId));

        taskService.act(taskId, "ARCHIVE", "已切流完成");

        assertEquals(TaskState.ARCHIVED, taskDao.stateOf(taskId).orElseThrow());
        assertFalse(runtime.isRunning(taskId), "ARCHIVED 任务不应继续消费");
    }

    @Test
    @DisplayName("RUNNING 任务切模式：仍按既有规则拒绝（会话不受影响）")
    void should_rejectModeSwitch_When_taskRunning() {
        long taskId = preparedTask(TaskState.RUNNING);
        runtime.start(taskId);

        assertThrows(DbSyncException.class,
                () -> cdcService.switchMode(taskId, new CdcModeRequest("TIMESTAMP_VERSION", "t")));
        assertTrue(runtime.isRunning(taskId), "被拒绝的切换不应动会话");
        assertEquals(IncrementalMode.TRIGGER_LOG,
                taskDao.findById(taskId).orElseThrow().getIncrementalMode());
    }

    @Test
    @DisplayName("非 RUNNING 任务切模式：会话被收敛为停止（模式语义已变，不能让老会话继续跑）")
    void should_stopConsumer_When_modeSwitchedOnPausedTask() {
        long taskId = preparedTask(TaskState.PAUSED);
        // 造出"状态已 PAUSED 但会话还在跑"的漂移（正是对账要处理的形态）
        runtime.start(taskId);
        assertTrue(runtime.isRunning(taskId));

        cdcService.switchMode(taskId, new CdcModeRequest("TIMESTAMP_VERSION", "改轮询模式"));

        assertFalse(runtime.isRunning(taskId), "切模式后不应残留旧会话");
        assertEquals(IncrementalMode.TIMESTAMP_VERSION,
                taskDao.findById(taskId).orElseThrow().getIncrementalMode());
    }

    @Test
    @DisplayName("启动恢复：库里预置 RUNNING 任务，recover 后会话在跑")
    void should_startConsumer_When_applicationReadyRecovers() {
        long taskId = preparedTask(TaskState.RUNNING);
        assertFalse(runtime.isRunning(taskId), "恢复前本实例没有会话");

        int started = supervisor.recover();

        assertEquals(1, started, "应拉起一个 RUNNING 任务");
        assertTrue(runtime.isRunning(taskId), "恢复后必须有消费会话");
        assertTrue(runtime.status(taskId).running());
    }

    @Test
    @DisplayName("启动恢复：任务不存在映射时只留 lastError，不影响应用启动")
    void should_notFailRecovery_When_mappingMissing() {
        // 有 RUNNING 任务但没有启用映射：装配必然失败
        long taskId = newTask(TaskState.RUNNING, IncrementalMode.TRIGGER_LOG, 2, 1);

        int started = supervisor.recover();

        assertEquals(0, started);
        assertFalse(runtime.isRunning(taskId));
        String lastError = runtime.status(taskId).lastError();
        assertTrue(lastError != null && lastError.contains("表映射"),
                "失败原因应可见，实际：" + lastError);
    }

    @Test
    @DisplayName("状态漂移对账：库里已不是 RUNNING，reconcile 停掉会话")
    void should_stopDriftedSession_When_reconcile() {
        long taskId = preparedTask(TaskState.RUNNING);
        runtime.start(taskId);
        assertTrue(runtime.isRunning(taskId));

        // 模拟"别的实例把它取消了"
        jdbc.update("UPDATE sync_task SET state=? WHERE id=?", TaskState.FAILED.name(),
                Long.valueOf(taskId));

        int stopped = supervisor.reconcile();

        assertEquals(1, stopped);
        assertFalse(runtime.isRunning(taskId), "对账后不应再有会话");
    }

    // ---------------------------------------------------------------- 辅助

    /**
     * 造一个可消费的任务：指定状态 + 1 个启用映射 + 目标表。
     *
     * @param state 任务状态
     * @return 任务 ID
     */
    private long preparedTask(TaskState state) {
        long taskId = newTask(state, IncrementalMode.TRIGGER_LOG, 1, 1);
        String table = "t_life_" + taskId;
        newMapping(taskId, table);
        createTargetTable(table);
        return taskId;
    }

    /**
     * 把任务状态写成真实引擎会写的值（mock 的桩用）。
     *
     * @param taskId 任务 ID
     * @param state  目标状态
     */
    private void setState(long taskId, TaskState state) {
        jdbc.update("UPDATE sync_task SET state=? WHERE id=?", state.name(),
                Long.valueOf(taskId));
    }
}
