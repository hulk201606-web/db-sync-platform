package com.dbsync.api.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.cdc.ChangeQueue;
import com.dbsync.cdc.CheckpointCursorStore;
import com.dbsync.cdc.TriggerLogPoller;
import com.dbsync.common.enums.ChangeStatus;
import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.model.Checkpoint;
import com.dbsync.common.model.Watermark;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>采集 → 消费</b>真端到端验收（CDC P0 的最后一块）。
 *
 * <p>本轮之前 {@code TriggerLogPoller.pollOnce} 全仓库没有生产调用方，
 * 触发器写进源库日志表的变更<b>没有任何人搬进</b> {@code sync_change_log}，
 * 消费侧再正确也无数据可消费。本用例走的是完整生产链路：
 *
 * <pre>
 *   源库日志表（模拟触发器写入）
 *     → TriggerLogPoller.pollOnce（真 H2 源库、真方言 SQL、真位点存储）
 *     → sync_change_log
 *     → ChangeConsumer / GroupWorker（真消费者线程池 + 真组租约）
 *     → UpsertApplier（真目标库 H2）
 *     → 目标表 + watermark 前进
 * </pre>
 *
 * <p>四条断言各自证明一件事：
 * <ol>
 *   <li>采集真的把源日志搬进了队列（此前一直是 0 条）；</li>
 *   <li>消费真的把队列落到了目标表，且日志转 APPLIED、水位前进；</li>
 *   <li>位点真的在推进（第二批只采到新增的 1 条，不是重复搬历史）；</li>
 *   <li>位点真的持久化了（<b>新建一个 poller 实例</b>=模拟进程重启，不重复采集）。</li>
 * </ol>
 */
class CdcCaptureEndToEndTest extends CdcRuntimeTestSupport {

    private static final String TABLE = "t_capture_e2e";

    @Test
    @DisplayName("源库日志 → 采集 → 消费 → 目标表/水位；再插 1 条 → 继续；新 poller 实例不重复采集")
    void should_captureThenConsume_AndSurviveRestart() {
        long taskId = newTask(TaskState.RUNNING, IncrementalMode.TRIGGER_LOG, 1, 1);
        // 采集权窗口默认取任务轮询周期；单测里"连续两轮"之间没有真实的周期间隔，
        // 因此把任务周期设为 0（=未配置），让窗口回落到 store 的默认值（1ns，等价"随时可认领"）。
        // 窗口本身的语义（窗口内被别的实例挡住）由 CheckpointCursorStoreTest 单独验证。
        jdbc.update("UPDATE sync_task SET poll_interval_ms=0 WHERE id=?", taskId);
        long mappingId = newMapping(taskId, TABLE);
        createTargetTable(TABLE);
        String logTable = createTriggerLog("src_" + TABLE);
        registerTriggerDef(taskId, mappingId, logTable);

        // 预置 2 条变更（模拟触发器已写入源库日志表）
        appendTriggerLog(logTable, "UPDATE", "[1]", null, "{\"ID\":1,\"NAME\":\"alpha\"}");
        appendTriggerLog(logTable, "UPDATE", "[2]", null, "{\"ID\":2,\"NAME\":\"beta\"}");

        TriggerLogPoller poller = newTriggerPoller();

        // ① 采集：源日志 → sync_change_log
        assertEquals(2, poller.pollOnce(taskId, mappingId), "第一轮应采到 2 条");
        assertEquals(2L, changeLogDao.countByStatus(taskId, ChangeStatus.NEW),
                "采集后队列里必须有 2 条待消费变更（此前这里是 0 条 = 上线阻断项）");

        // ② 消费：队列 → 目标表
        runtime.start(taskId);
        awaitUntil(() -> changeLogDao.countByStatus(taskId, ChangeStatus.NEW) == 0L
                        && watermark(taskId, 0).map(w -> w.getSafeSeq() >= 2L).orElse(false),
                "两条变更被消费且水位推进到 2");
        assertEquals(2L, changeLogDao.countByStatus(taskId, ChangeStatus.APPLIED));
        assertEquals("alpha", nameOf(TABLE, 1L), "目标表必须真的出现第 1 行");
        assertEquals("beta", nameOf(TABLE, 2L), "目标表必须真的出现第 2 行");
        Watermark watermark = watermark(taskId, 0).orElseThrow();
        assertEquals(2L, watermark.getSafeSeq());

        // ③ 位点推进：再插 1 条 → 只采到 1 条（证明位点不是"每次都从头搬"）
        appendTriggerLog(logTable, "UPDATE", "[3]", null, "{\"ID\":3,\"NAME\":\"gamma\"}");
        assertEquals(1, poller.pollOnce(taskId, mappingId), "第二轮只应采到新增的 1 条");
        awaitUntil(() -> "gamma".equals(nameOf(TABLE, 3L)), "第 3 行被消费落库");
        assertEquals(3L, changeLogDao.countByStatus(taskId, ChangeStatus.APPLIED));

        // ④ 模拟重启：换一个全新的 poller 实例（内存状态全丢），位点必须从 sync_checkpoint 恢复
        TriggerLogPoller restarted = newTriggerPoller();
        long before = changeLogTotal(taskId);
        assertEquals(0, restarted.pollOnce(taskId, mappingId),
                "重启后不得重复采集已处理的日志（位点落在 sync_checkpoint 里）");
        assertEquals(before, changeLogTotal(taskId), "change_log 行数不应因重启而变化");

        Checkpoint cursor = pollerCheckpoint(taskId, mappingId).orElse(null);
        assertNotNull(cursor, "采集位点必须落库到 sync_checkpoint(POLLER)");
        assertNotNull(cursor.getLastLogId(), "位点必须记录 last_log_id");
        assertEquals(3L, cursor.getLastLogId().longValue(),
                "位点应停在源库日志表的第 3 条");
        assertTrue(cursor.getVersion() > 0, "位点行版本应已推进（用于多实例采集权 CAS）");
    }

    @Test
    @DisplayName("空日志表：采集返回 0、位点不推进（不会把空轮当成已消费）")
    void should_notAdvanceCursor_When_logTableEmpty() {
        long taskId = newTask(TaskState.RUNNING, IncrementalMode.TRIGGER_LOG, 1, 1);
        jdbc.update("UPDATE sync_task SET poll_interval_ms=0 WHERE id=?", taskId);
        long mappingId = newMapping(taskId, TABLE);
        String logTable = createTriggerLog("src_" + TABLE);
        registerTriggerDef(taskId, mappingId, logTable);

        assertEquals(0, newTriggerPoller().pollOnce(taskId, mappingId));
        assertEquals(0L, changeLogTotal(taskId));
        assertEquals(0L, pollerCheckpoint(taskId, mappingId)
                .map(Checkpoint::getLastLogId)
                .orElse(Long.valueOf(0L)).longValue(), "空轮不应推进位点");
    }

    /**
     * 建一个走<b>生产位点存储</b>的触发器轮询器。
     *
     * <p>采集权窗口取 1ns：本用例驱动的是"位点持久化 + 数据流"，
     * 不希望被跨实例互斥窗口挡住。窗口语义由 {@code CheckpointCursorStoreTest} 单独验证。
     *
     * @return 轮询器
     */
    private TriggerLogPoller newTriggerPoller() {
        return new TriggerLogPoller(registry, taskDao, mappingDao, triggerDefDao,
                new ChangeQueue(changeLogDao), 100,
                new CheckpointCursorStore(checkpointDao, java.time.Duration.ofNanos(1L)));
    }
}
