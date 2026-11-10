package com.dbsync.cdc.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.dbsync.cdc.ChangeQueue;
import com.dbsync.common.enums.ChangeOp;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.model.ChangeEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * T33 验收：SDK 双写的"登记 → 刷入"语义（接口预留，但默认实现可用且有测试）。
 */
class DbSyncWriterTest {

    private static final long TASK_ID = 99L;

    private ChangeQueue queue;
    private SingleNodeDbSyncWriter writer;

    /**
     * 装配（不攒批，手动 flush）。
     */
    @BeforeEach
    void setUpWriter() {
        queue = mock(ChangeQueue.class);
        writer = new SingleNodeDbSyncWriter(queue, TASK_ID,
                Map.of("T_ORD", Long.valueOf(10L)), Map.of("T_ORD", List.of("id")), 16, 0);
    }

    @Test
    @DisplayName("should_bufferThenFlush_When_writeTwice：两次登记一次刷入")
    void should_bufferThenFlush_When_writeTwice() {
        writer.write("t_ord", Map.of("id", Long.valueOf(1L), "name", "a"));
        writer.write("t_ord", Map.of("id", Long.valueOf(1L), "name", "b"));
        assertEquals(2, writer.buffered(), "未 flush 前应留在缓冲区");

        writer.flush();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChangeEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(queue).enqueue(captor.capture());
        List<ChangeEvent> events = captor.getValue();
        assertEquals(2, events.size());
        assertEquals(1L, events.get(0).getSeq());
        assertEquals(2L, events.get(1).getSeq(), "seq 必须单调，组内顺序才可靠");
        assertEquals(events.get(0).getGroupNo(), events.get(1).getGroupNo(),
                "同一主键必须落同一组");
        assertEquals(ChangeOp.UPDATE, events.get(0).getOp(), "写入按 upsert 语义登记");
        assertEquals(0, writer.buffered());
    }

    @Test
    @DisplayName("should_notFlushAgain_When_bufferEmpty：空缓冲不产生空刷入")
    void should_notFlushAgain_When_bufferEmpty() {
        writer.write("t_ord", Map.of("id", Long.valueOf(1L), "name", "a"));
        writer.flush();
        writer.flush();

        verify(queue, times(1)).enqueue(anyList());
    }

    @Test
    @DisplayName("should_registerDelete_When_deleteCalled：删除也走同一队列")
    void should_registerDelete_When_deleteCalled() {
        writer.delete("t_ord", List.of(Long.valueOf(9L)));
        writer.flush();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChangeEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(queue).enqueue(captor.capture());
        assertEquals(ChangeOp.DELETE, captor.getValue().get(0).getOp());
    }

    @Test
    @DisplayName("should_rejectUnknownTable_When_write：未配置映射的表直接报错")
    void should_rejectUnknownTable_When_write() {
        assertThrows(DbSyncException.class,
                () -> writer.write("t_unknown", Map.of("id", Long.valueOf(1L))));
    }

    @Test
    @DisplayName("should_reject_When_deleteTupleLengthMismatch：删除元组长度必须匹配")
    void should_reject_When_deleteTupleLengthMismatch() {
        assertThrows(DbSyncException.class,
                () -> writer.delete("t_ord", List.of(Long.valueOf(1L), Long.valueOf(2L))));
    }

    @Test
    @DisplayName("should_keepBatchAndMarkUnhealthy_When_flushFails：刷失败不丢数据")
    void should_keepBatchAndMarkUnhealthy_When_flushFails() {
        doThrow(new DbSyncException(com.dbsync.common.exception.ErrorCode.AUDIT_WRITE_FAILED,
                "queue down")).when(queue).enqueue(anyList());
        writer.write("t_ord", Map.of("id", Long.valueOf(1L), "name", "a"));

        assertThrows(DbSyncException.class, writer::flush);

        assertFalse(writer.isHealthy(), "刷失败必须标记不健康，供业务侧熔断");
        assertEquals(1, writer.buffered(), "失败的批次必须留在缓冲区，不能丢");
    }

    @Test
    @DisplayName("should_autoFlush_When_thresholdReached：达到阈值自动刷")
    void should_autoFlush_When_thresholdReached() {
        SingleNodeDbSyncWriter auto = new SingleNodeDbSyncWriter(queue, TASK_ID,
                Map.of("T_ORD", Long.valueOf(10L)), Map.of("T_ORD", List.of("id")), 16, 2);

        auto.write("t_ord", Map.of("id", Long.valueOf(1L), "name", "a"));
        assertEquals(1, auto.buffered());
        auto.write("t_ord", Map.of("id", Long.valueOf(2L), "name", "b"));

        assertEquals(0, auto.buffered(), "达到阈值后应自动刷空");
        assertTrue(auto.isHealthy());
    }
}
