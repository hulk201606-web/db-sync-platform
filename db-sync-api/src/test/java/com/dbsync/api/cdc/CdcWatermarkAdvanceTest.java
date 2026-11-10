package com.dbsync.api.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.enums.ChangeStatus;
import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.model.Watermark;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>上线阻断项的验收测试</b>：增量变更是否真的落到了目标库。
 *
 * <p>走的是生产链路：{@link CdcContextFactory} → {@code ChangeConsumer} → {@code GroupWorker}
 * → {@code UpsertApplier}，只把"数据源注册表"换成 H2（方言仍是 MySQL，与 cdc 模块既有手法一致）。
 * 因此三条断言都有实际意义：
 * <ol>
 *   <li>{@code sync_change_log.status} 由 NEW 变 APPLIED —— 消费者真的处理了；</li>
 *   <li>{@code sync_change_watermark.safe_seq} 前进到 2 —— 水位线推进（不变式 I4）；</li>
 *   <li>目标表出现 {@code id=1/2} 的行 —— 数据真的写进去了，而不是只在元数据库里"标记完成"。</li>
 * </ol>
 */
class CdcWatermarkAdvanceTest extends CdcRuntimeTestSupport {

    private static final String TABLE = "t_cdc_advance";

    @Test
    @DisplayName("2 条待消费变更 → 水位推进 + 日志 APPLIED + 目标表落库")
    void should_applyChangesAndAdvanceWatermark() {
        long taskId = newTask(TaskState.RUNNING, IncrementalMode.TRIGGER_LOG, 1, 1);
        long mappingId = newMapping(taskId, TABLE);
        createTargetTable(TABLE);
        changeLogDao.insertBatch(List.of(
                change(taskId, mappingId, 1L, "alpha", 1L, 0),
                change(taskId, mappingId, 2L, "beta", 2L, 0)));
        assertEquals(2L, changeLogDao.countByStatus(taskId, ChangeStatus.NEW));

        runtime.start(taskId);
        assertTrue(runtime.isRunning(taskId), "会话应已在跑");

        awaitUntil(() -> changeLogDao.countByStatus(taskId, ChangeStatus.NEW) == 0L
                        && watermark(taskId, 0).map(w -> w.getSafeSeq() >= 2L).orElse(false),
                "两条变更被消费且水位推进到 2");

        // ① 日志：两条都标成 APPLIED（没有落进 SKIPPED/DEAD）
        assertEquals(2L, changeLogDao.countByStatus(taskId, ChangeStatus.APPLIED));
        assertEquals(0L, changeLogDao.countByStatus(taskId, ChangeStatus.DEAD));
        // ② 水位：无未应用变更时水位 = 该组 MAX(seq) = 2
        Watermark watermark = watermark(taskId, 0).orElseThrow();
        assertEquals(2L, watermark.getSafeSeq());
        // ③ 目标库：数据真的写进去了
        assertEquals("alpha", nameOf(TABLE, 1L));
        assertEquals("beta", nameOf(TABLE, 2L));

        runtime.stop(taskId);
        assertFalse(runtime.isRunning(taskId), "stop 后会话应结束");
    }

    @Test
    @DisplayName("消费是持续的：第二批变更到达后继续被应用")
    void should_keepConsuming_When_newChangesArrive() {
        long taskId = newTask(TaskState.RUNNING, IncrementalMode.TRIGGER_LOG, 1, 1);
        long mappingId = newMapping(taskId, TABLE);
        createTargetTable(TABLE);
        changeLogDao.insertBatch(List.of(change(taskId, mappingId, 10L, "first", 1L, 0)));

        runtime.start(taskId);
        awaitUntil(() -> "first".equals(nameOf(TABLE, 10L)), "第一条变更落库");

        changeLogDao.insertBatch(List.of(change(taskId, mappingId, 11L, "second", 2L, 0)));
        awaitUntil(() -> "second".equals(nameOf(TABLE, 11L)), "第二条变更落库");

        awaitUntil(() -> watermark(taskId, 0).map(w -> w.getSafeSeq() >= 2L).orElse(false),
                "水位推进到 2");
        assertEquals(2L, changeLogDao.countByStatus(taskId, ChangeStatus.APPLIED));
    }
}
