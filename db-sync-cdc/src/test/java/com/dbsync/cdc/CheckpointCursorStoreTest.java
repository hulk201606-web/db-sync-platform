package com.dbsync.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.enums.ScopeType;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import com.dbsync.metadata.dao.CheckpointDao;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 采集位点持久化与"采集权窗口"测试。
 *
 * <p>这两件事是本轮修复的核心风险点：
 * <ul>
 *   <li><b>位点必须落库</b>：否则每次发版都把源库日志表从头重扫一遍
 *       （时间戳模式更糟：停机窗口内的变更直接漏掉）；</li>
 *   <li><b>采集权窗口必须真挡得住</b>：否则多实例部署下同一批日志被各搬一遍，
 *       {@code sync_change_log} 翻倍。</li>
 * </ul>
 */
class CheckpointCursorStoreTest extends CdcTestSupport {

    private CheckpointDao checkpointDao;
    private SyncTask task;
    private TableMapping mapping;

    /**
     * 建位点 DAO 与一个任务/映射。
     */
    @BeforeEach
    void setUpStore() {
        checkpointDao = new CheckpointDao(jdbc);
        task = newTask(2, 1, IncrementalMode.TRIGGER_LOG);
        mapping = newMapping(task.getId(), "t_cursor");
    }

    @Test
    @DisplayName("首次认领：建位点行并拿到采集权，位点从 0 开始")
    void should_createRowAndClaim_When_firstTime() {
        CheckpointCursorStore store = new CheckpointCursorStore(checkpointDao);

        assertTrue(store.tryClaim(task.getId(), mapping.getId(), null));
        PollerCursorStore.Cursor cursor = store.load(task.getId(), mapping.getId());
        assertEquals(0L, cursor.lastLogId(), "初始化位点是 0（不是 null，读库时语义清晰）");
        assertNull(cursor.lastCommitTs());
        assertTrue(cursor.version() >= 0);
    }

    @Test
    @DisplayName("位点持久化：换一个 store 实例仍能读到同一位点（重启不重放）")
    void should_shareCursor_AcrossStoreInstances() {
        CheckpointCursorStore first = new CheckpointCursorStore(checkpointDao);
        assertTrue(first.tryClaim(task.getId(), mapping.getId(), null));
        first.advance(task.getId(), mapping.getId(), Long.valueOf(42L), null);

        // 模拟进程重启：全新实例，无任何内存状态
        CheckpointCursorStore restarted = new CheckpointCursorStore(checkpointDao);
        assertEquals(42L, restarted.load(task.getId(), mapping.getId()).lastLogId(),
                "重启后位点必须从库里恢复（否则会重复采集 42 条历史）");
    }

    @Test
    @DisplayName("时间戳模式：last_commit_ts 同样落库并恢复")
    void should_persistTimestampCursor() {
        CheckpointCursorStore store = new CheckpointCursorStore(checkpointDao);
        store.tryClaim(task.getId(), mapping.getId(), null);
        Instant commitTs = Instant.parse("2025-03-01T10:00:00Z");
        store.advance(task.getId(), mapping.getId(), null, commitTs);

        PollerCursorStore.Cursor reloaded =
                new CheckpointCursorStore(checkpointDao).load(task.getId(), mapping.getId());
        assertEquals(commitTs, reloaded.lastCommitTs(),
                "时间戳位点必须落库，否则重启后会漏掉停机窗口内的变更");
    }

    @Test
    @DisplayName("采集权窗口：窗口内第二次认领必须失败，窗口过后可再认领")
    void should_blockSecondClaim_WithinWindow() throws Exception {
        // 窗口取 200ms，便于在单测里跨过
        CheckpointCursorStore store = new CheckpointCursorStore(checkpointDao,
                Duration.ofMillis(200L));
        Duration window = Duration.ofMillis(200L);

        assertTrue(store.tryClaim(task.getId(), mapping.getId(), window), "第一次认领应成功");
        assertFalse(store.tryClaim(task.getId(), mapping.getId(), window),
                "窗口内第二次认领必须失败（否则同一批日志会被采两遍）");

        Thread.sleep(260L);
        assertTrue(store.tryClaim(task.getId(), mapping.getId(), window),
                "窗口过后必须能再次认领（同一实例按周期轮转 / 其他实例接手）");
    }

    @Test
    @DisplayName("采集权窗口按传入值生效：同一行上更长的窗口挡住更早的重认领")
    void should_honourPerCallWindow() {
        CheckpointCursorStore store = new CheckpointCursorStore(checkpointDao,
                Duration.ofSeconds(30L));

        assertTrue(store.tryClaim(task.getId(), mapping.getId(), Duration.ofSeconds(30L)));
        assertFalse(store.tryClaim(task.getId(), mapping.getId(), Duration.ofSeconds(30L)));
    }

    @Test
    @DisplayName("位点被同步写入 last_log_id 与 last_seq（两列一致，便于运维判读）")
    void should_writeBothLogIdAndSeq() {
        CheckpointCursorStore store = new CheckpointCursorStore(checkpointDao);
        store.tryClaim(task.getId(), mapping.getId(), null);
        store.advance(task.getId(), mapping.getId(), Long.valueOf(7L), null);

        com.dbsync.common.model.Checkpoint ckpt = checkpointDao
                .load(task.getId(), mapping.getId(), ScopeType.POLLER, 0).orElseThrow();
        assertEquals(7L, ckpt.getLastLogId().longValue());
        assertEquals(7L, ckpt.getLastSeq().longValue());
    }

    @Test
    @DisplayName("进程内实现（仅单测）：位点同样前进，且不阻塞连续认领")
    void should_workForInMemoryImpl() {
        InMemoryCursorStore store = new InMemoryCursorStore();
        long taskId = task.getId();
        long mappingId = mapping.getId();

        assertTrue(store.tryClaim(taskId, mappingId, null));
        store.advance(taskId, mappingId, Long.valueOf(5L), null);
        assertTrue(store.tryClaim(taskId, mappingId, null), "单进程实现不做跨实例互斥");
        assertEquals(5L, store.load(taskId, mappingId).lastLogId());
    }
}
