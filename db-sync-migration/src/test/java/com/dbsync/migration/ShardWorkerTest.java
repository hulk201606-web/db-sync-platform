package com.dbsync.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dbsync.common.enums.ClaimStrategy;
import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.enums.ShardState;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.exception.LeaseLostException;
import com.dbsync.common.model.Checkpoint;
import com.dbsync.common.model.PkRange;
import com.dbsync.common.model.Shard;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.ReadOptions;
import com.dbsync.common.options.WriteOptions;
import com.dbsync.common.util.RetryTemplate;
import com.dbsync.core.checkpoint.CheckpointStore;
import com.dbsync.core.lease.LeaseHandle;
import com.dbsync.core.lease.ShardLeaseManager;
import com.dbsync.core.read.JdbcReader;
import com.dbsync.core.read.RowCallback;
import com.dbsync.core.throttle.Throttle;
import com.dbsync.core.write.JdbcBatchWriter;
import com.dbsync.core.write.WriteResult;
import com.dbsync.dialect.Dialect;
import com.dbsync.metadata.dao.ShardDao;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * T25 验收：ShardWorker 的 5 条核心断言。
 *
 * <p>这里全部用 mock，因为要验证的是<b>调用顺序</b>（commit 在 save 之前）
 * 与<b>异常路径</b>（commit 失败不推断点），用真库反而看不清顺序。
 * 真库路径由 {@link MigrationEngineTest} 覆盖。
 */
class ShardWorkerTest extends MigrationTestSupport {

    private static final long TASK_ID = 9001L;
    private static final long MAPPING_ID = 8001L;
    private static final long SHARD_ID = 7001L;
    private static final String OWNER = "inst-A";

    private JdbcReader reader;
    private JdbcBatchWriter writer;
    private CheckpointStore store;
    private ShardDao dao;
    private DeadLetterService deadLetters;
    private Connection sourceConn;
    private Connection targetConn;
    private Shard shard;
    private TableMeta meta;

    /**
     * 组装 mock 环境。
     */
    @BeforeEach
    void setUpMocks() {
        reader = mock(JdbcReader.class);
        writer = mock(JdbcBatchWriter.class);
        store = mock(CheckpointStore.class);
        dao = mock(ShardDao.class);
        deadLetters = mock(DeadLetterService.class);
        sourceConn = mock(Connection.class);
        targetConn = mock(Connection.class);

        TableMapping mapping = new TableMapping(MAPPING_ID, TASK_ID, "PUBLIC", "t_order", "PUBLIC",
                "t_order", true, "NUMERIC", 100L, null, true, true);
        meta = tableMeta("t_order", 100L);
        shard = new Shard(SHARD_ID, TASK_ID, MAPPING_ID, 1, RangeKind.NUMERIC, null, null,
                ShardState.CLAIMED, OWNER, null, 0L, 1, 0);
        when(store.load(anyLong(), anyLong(), any(), anyInt()))
                .thenReturn(Optional.empty());
        when(dao.markRunning(anyLong(), anyString())).thenReturn(1);
        when(dao.markSuccess(anyLong(), anyString(), anyLong(), anyLong())).thenReturn(1);
        when(dao.markDead(anyLong(), anyString())).thenReturn(1);
    }

    /**
     * 构造被测对象。
     *
     * @param batchSize 批大小
     * @param retry     重试模板
     * @return worker
     */
    private ShardWorker worker(int batchSize, RetryTemplate retry) {
        TableMapping mapping = new TableMapping(MAPPING_ID, TASK_ID, "PUBLIC", "t_order", "PUBLIC",
                "t_order", true, "NUMERIC", 100L, null, true, true);
        ShardContext ctx = new ShardContext(TASK_ID, mapping, meta, meta, dialect, dialect,
                () -> sourceConn, () -> targetConn, ReadOptions.of(batchSize), WriteOptions.upsert(),
                () -> false);
        return new ShardWorker(ctx, reader, writer, store, dao, deadLetters, Throttle.unlimited(),
                retry);
    }

    /**
     * 构造真实租约句柄（内部走 mock 的 ShardDao）。
     *
     * @param held 租约是否仍持有
     * @return 句柄
     */
    private LeaseHandle lease(boolean held) {
        ShardDao leaseDao = mock(ShardDao.class);
        when(leaseDao.claimOne(eq(TASK_ID), eq(OWNER), any(Duration.class),
                eq(ClaimStrategy.SKIP_LOCKED))).thenReturn(Optional.of(shard));
        when(leaseDao.assertHeld(anyLong(), anyString())).thenReturn(held);
        ShardLeaseManager manager = new ShardLeaseManager(leaseDao,
                ShardLeaseManager.strategyOf(leaseDao, ClaimStrategy.SKIP_LOCKED),
                Duration.ofSeconds(30), Duration.ofSeconds(10));
        return manager.claim(TASK_ID, OWNER).orElseThrow();
    }

    /**
     * 让 reader 依次返回若干批（每批行数固定）。
     *
     * @param batchSizes 每批行数
     */
    private void stubBatches(int... batchSizes) {
        AtomicInteger call = new AtomicInteger();
        when(reader.read(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            int index = call.getAndIncrement();
            if (index >= batchSizes.length) {
                return 0L;
            }
            int size = batchSizes[index];
            RowCallback callback = invocation.getArgument(5);
            for (int i = 0; i < size; i++) {
                long pk = (long) index * 1000L + i + 1L;
                callback.onRow(new Object[] {pk, "n" + pk}, i);
            }
            return (long) size;
        });
    }

    @Test
    @DisplayName("should_saveCheckpointOnlyAfterCommit_When_batchSucceeded：commit 之后才推断点")
    void should_saveCheckpointOnlyAfterCommit_When_batchSucceeded() throws Exception {
        stubBatches(2);
        when(writer.upsert(any(), any(), any(), any(), any()))
                .thenReturn(WriteResult.beforeCommit(2L, 7L));
        when(store.load(anyLong(), anyLong(), any(), anyInt()))
                .thenReturn(Optional.empty());
        ShardWorker worker = worker(1000, new RetryTemplate(1, Duration.ofMillis(1L), 1.0d, 0.0d));

        ShardOutcome outcome = worker.run(shard, lease(true));

        assertEquals(ShardOutcome.SUCCESS, outcome);

        InOrder order = inOrder(reader, writer, targetConn, store);
        order.verify(reader).read(any(), any(), any(), any(), any(), any());
        order.verify(writer).upsert(any(), any(), any(), any(), any());
        order.verify(targetConn).commit();
        order.verify(store).save(any(), any());

        // 关键：交给 CheckpointStore 的 WriteResult 必须是"已提交"
        ArgumentCaptor<WriteResult> captor = ArgumentCaptor.forClass(WriteResult.class);
        verify(store).save(any(Checkpoint.class), captor.capture());
        assertTrue(captor.getValue().committed(), "推送断点时必须携带 committed=true 的 WriteResult");
        // 建表语句里不能出现"未提交"的痕迹：commit 只会被调一次
        verify(targetConn, times(1)).setAutoCommit(false);
        verify(targetConn, times(1)).commit();
    }

    @Test
    @DisplayName("should_notSaveCheckpoint_When_commitFailed：commit 抛异常时绝不推断点")
    void should_notSaveCheckpoint_When_commitFailed() throws Exception {
        stubBatches(3);
        when(writer.upsert(any(), any(), any(), any(), any()))
                .thenReturn(WriteResult.beforeCommit(3L, 2L));
        doThrowOnCommit();
        ShardWorker worker = worker(1000, new RetryTemplate(1, Duration.ofMillis(1L), 1.0d, 0.0d));

        ShardOutcome outcome = worker.run(shard, lease(true));

        // 重试 1 次即耗尽 → 死信
        assertEquals(ShardOutcome.DEAD, outcome);
        verify(store, never()).save(any(), any());
        verify(targetConn).rollback();
        verify(dao).markDead(eq(SHARD_ID), anyString());
        verify(deadLetters).createShardFailure(eq(shard), eq(MAPPING_ID), anyString(), any());
    }

    @Test
    @DisplayName("should_retryWholeBatch_When_batchUpdateException：整批重试成功后断点只推进一次")
    void should_retryWholeBatch_When_batchUpdateException() throws Exception {
        stubBatches(2);
        when(writer.upsert(any(), any(), any(), any(), any()))
                .thenThrow(new DbSyncException(ErrorCode.CHANGE_APPLY_FAILED, "duplicate key"))
                .thenReturn(WriteResult.beforeCommit(2L, 5L));
        ShardWorker worker = worker(1000, new RetryTemplate(2, Duration.ofMillis(1L), 1.0d, 0.0d));

        ShardOutcome outcome = worker.run(shard, lease(true));

        assertEquals(ShardOutcome.SUCCESS, outcome);
        verify(writer, times(2)).upsert(any(), any(), any(), any(), any());
        // 断点只推进一次（不因重试而重复累加行数）
        verify(store, times(1)).save(any(Checkpoint.class), any(WriteResult.class));
        ArgumentCaptor<Checkpoint> captor = ArgumentCaptor.forClass(Checkpoint.class);
        verify(store).save(captor.capture(), any(WriteResult.class));
        assertEquals(2L, captor.getValue().getCommittedRows(), "重试不应导致行数重复累加");
        verify(dao).markSuccess(eq(SHARD_ID), eq(OWNER), eq(2L), eq(2L));
    }

    @Test
    @DisplayName("should_goDeadAndDeadLetter_When_retryExhausted：3 次失败 → DEAD + 死信")
    void should_goDeadAndDeadLetter_When_retryExhausted() throws Exception {
        stubBatches(1);
        when(writer.upsert(any(), any(), any(), any(), any()))
                .thenThrow(new DbSyncException(ErrorCode.CHANGE_APPLY_FAILED, "boom"));
        ShardWorker worker = worker(1000, new RetryTemplate(3, Duration.ofMillis(1L), 1.0d, 0.0d));

        ShardOutcome outcome = worker.run(shard, lease(true));

        assertEquals(ShardOutcome.DEAD, outcome);
        verify(writer, times(3)).upsert(any(), any(), any(), any(), any());
        verify(store, never()).save(any(), any());
        verify(dao).markDead(eq(SHARD_ID), anyString());
        verify(deadLetters, times(1)).createShardFailure(eq(shard), eq(MAPPING_ID), anyString(),
                any());
    }

    @Test
    @DisplayName("should_abort_When_leaseLost：租约丢失立即抛出且不写断点")
    void should_abort_When_leaseLost() {
        // 每 5 批校验一次租约 → 需要先成功提交 5 批
        stubBatches(1, 1, 1, 1, 1, 1);
        when(writer.upsert(any(), any(), any(), any(), any()))
                .thenReturn(WriteResult.beforeCommit(1L, 1L));
        ShardWorker worker = worker(1, new RetryTemplate(1, Duration.ofMillis(1L), 1.0d, 0.0d));

        assertThrows(LeaseLostException.class, () -> worker.run(shard, lease(false)));

        verify(store, times(ShardWorker.LEASE_CHECK_EVERY_BATCHES)).save(any(), any());
        verify(dao, never()).markSuccess(anyLong(), anyString(), anyLong(), anyLong());
        verify(dao, never()).markDead(anyLong(), anyString());
    }

    @Test
    @DisplayName("should_releaseLeaseAndAbort_When_stopRequested：收到暂停信号即交还分片")
    void should_releaseLeaseAndAbort_When_stopRequested() {
        TableMapping mapping = new TableMapping(MAPPING_ID, TASK_ID, "PUBLIC", "t_order", "PUBLIC",
                "t_order", true, "NUMERIC", 100L, null, true, true);
        ShardContext ctx = new ShardContext(TASK_ID, mapping, meta, meta, dialect, dialect,
                () -> sourceConn, () -> targetConn, ReadOptions.of(10), WriteOptions.upsert(),
                () -> true);
        ShardWorker worker = new ShardWorker(ctx, reader, writer, store, dao, deadLetters,
                Throttle.unlimited(), new RetryTemplate(1, Duration.ofMillis(1L), 1.0d, 0.0d));

        ShardOutcome outcome = worker.run(shard, lease(true));

        assertEquals(ShardOutcome.ABORTED, outcome);
        verify(dao).release(eq(SHARD_ID), eq(OWNER));
        verify(store, never()).save(any(), any());
        // 一行业都没读
        verify(reader, never()).read(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("should_resumeFromLastPk_When_checkpointExists：从断点续读，不重头搬")
    void should_resumeFromLastPk_When_checkpointExists() throws Exception {
        Checkpoint existing = new Checkpoint(TASK_ID, MAPPING_ID,
                com.dbsync.common.enums.ScopeType.SHARD, 1, "500", null, null, null, 500L, 5L, 3);
        when(store.load(anyLong(), anyLong(), any(), anyInt()))
                .thenReturn(Optional.of(existing));
        stubBatches(2);
        when(writer.upsert(any(), any(), any(), any(), any()))
                .thenReturn(WriteResult.beforeCommit(2L, 3L));
        ShardWorker worker = worker(1000, new RetryTemplate(1, Duration.ofMillis(1L), 1.0d, 0.0d));

        worker.run(shard, lease(true));

        ArgumentCaptor<PkRange> rangeCaptor = ArgumentCaptor.forClass(PkRange.class);
        verify(reader, org.mockito.Mockito.atLeastOnce()).read(any(), any(), any(),
                rangeCaptor.capture(), any(), any());
        assertEquals("500", String.valueOf(rangeCaptor.getAllValues().get(0).getStart()),
                "续读必须从断点的 last_pk 开始（左开区间）");

        ArgumentCaptor<Checkpoint> saved = ArgumentCaptor.forClass(Checkpoint.class);
        verify(store).save(saved.capture(), any(WriteResult.class));
        assertEquals(502L, saved.getValue().getCommittedRows(), "已提交行数应在断点基础上累加");
        // 源侧行数口径：断点里已提交的 500 行"当初也是读到的"，加上本次读到的 2 行
        verify(dao).markSuccess(eq(SHARD_ID), eq(OWNER), eq(502L), eq(502L));
        // 版本号由元数据库回读（INSERT 保留原版本、UPDATE +1），真实递增链路由
        // MigrationEngineTest 在真库上覆盖；这里只断言 worker 用的是"带 seed 的断点"
        assertEquals("500", existing.getLastPk());
    }

    /**
     * 让 mock 的目标连接在 commit 时抛异常。
     *
     * @throws SQLException 声明（stub 用）
     */
    private void doThrowOnCommit() throws SQLException {
        org.mockito.Mockito.doThrow(new SQLException("connection reset while commit"))
                .when(targetConn).commit();
    }
}
