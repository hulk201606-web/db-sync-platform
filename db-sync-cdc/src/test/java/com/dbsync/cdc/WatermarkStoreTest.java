package com.dbsync.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.model.ChangeEvent;
import com.dbsync.common.model.Watermark;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T28 验收：水位仓的 I4（不越过缺口）、组独占、延迟估算。
 */
class WatermarkStoreTest extends CdcTestSupport {

    private static final int GROUP = 1;

    private WatermarkStore store;
    private long taskId;

    /**
     * 真实 DAO 装配。
     */
    @BeforeEach
    void setUpStore() {
        store = new WatermarkStore(watermarkDao, changeLogDao);
        taskId = newTask(16, 4, com.dbsync.common.enums.IncrementalMode.TRIGGER_LOG).getId();
    }

    /**
     * 写入一条指定 seq 的变更。
     *
     * @param seq 序号
     */
    private void insertSeq(long seq) {
        ChangeEvent event = new ChangeEvent(idGenerator.nextId(), taskId, 1L, GROUP, 1L, seq,
                Instant.now(), com.dbsync.common.enums.ChangeOp.UPDATE, List.of(Long.valueOf(1L)),
                null, java.util.Map.of("ID", Long.valueOf(1L)), null,
                com.dbsync.common.enums.ChangeStatus.NEW, 0, null, null);
        changeLogDao.insert(event, "t", "inst");
    }

    @Test
    @DisplayName("should_notAdvancePastGap_When_outOfOrder：只应用 1 和 3 时水位必须停在 1")
    void should_notAdvancePastGap_When_outOfOrder() {
        insertSeq(1L);
        insertSeq(2L);
        insertSeq(3L);
        // 模拟"乱序应用"：1 和 3 成功，2 仍是 NEW
        List<ChangeEvent> polled = changeLogDao.pollBatch(taskId, GROUP, 10);
        changeLogDao.markApplied(List.of(Long.valueOf(polled.get(0).getId()),
                Long.valueOf(polled.get(2).getId())),
                com.dbsync.common.enums.ChangeStatus.APPLIED);

        // 调用方"以为"已经应用到 3 了
        store.advance(taskId, GROUP, 3L, null);

        Watermark watermark = store.current(taskId, GROUP).orElseThrow();
        assertEquals(1L, watermark.getSafeSeq(),
                "I4：水位必须取 MIN(未应用 seq)-1 = 1，绝不能取 MAX(已应用) = 3");
    }

    @Test
    @DisplayName("should_advanceToMax_When_allApplied：全部应用后水位可到最大 seq")
    void should_advanceToMax_When_allApplied() {
        insertSeq(1L);
        insertSeq(2L);
        List<Long> ids = changeLogDao.pollBatch(taskId, GROUP, 10).stream()
                .map(e -> Long.valueOf(e.getId())).toList();
        changeLogDao.markApplied(ids, com.dbsync.common.enums.ChangeStatus.APPLIED);

        store.advance(taskId, GROUP, 2L, null);

        assertEquals(2L, store.current(taskId, GROUP).orElseThrow().getSafeSeq());
    }

    @Test
    @DisplayName("should_neverRegress_When_advanceWithSmallerSeq：水位只能前进（I3）")
    void should_neverRegress_When_advanceWithSmallerSeq() {
        insertSeq(1L);
        insertSeq(2L);
        changeLogDao.markApplied(changeLogDao.pollBatch(taskId, GROUP, 10).stream()
                .map(e -> Long.valueOf(e.getId())).toList(),
                com.dbsync.common.enums.ChangeStatus.APPLIED);
        store.advance(taskId, GROUP, 2L, null);

        store.advance(taskId, GROUP, 0L, null);

        assertEquals(2L, store.current(taskId, GROUP).orElseThrow().getSafeSeq(),
                "水位不允许回退");
    }

    @Test
    @DisplayName("should_acquireGroupExclusively_When_twoConsumers：同组只有一个消费者")
    void should_acquireGroupExclusively_When_twoConsumers() {
        boolean first = store.acquireGroup(taskId, GROUP, "consumer-A", Duration.ofSeconds(30L));
        boolean second = store.acquireGroup(taskId, GROUP, "consumer-B", Duration.ofSeconds(30L));

        assertTrue(first, "第一个消费者必须抢到");
        assertFalse(second, "第二个消费者不得抢到同一组");
        assertEquals("consumer-A", store.current(taskId, GROUP).orElseThrow().getConsumerOwner());
    }

    @Test
    @DisplayName("should_acquireAfterLeaseExpired_When_ownerCrashed：租约过期后可被接管")
    void should_acquireAfterLeaseExpired_When_ownerCrashed() {
        assertTrue(store.acquireGroup(taskId, GROUP, "consumer-A", Duration.ofMillis(1L)));
        sleep(30L);

        assertTrue(store.acquireGroup(taskId, GROUP, "consumer-B", Duration.ofSeconds(30L)),
                "持有者宕机后，组必须能被其他实例接管");
        assertEquals("consumer-B", store.current(taskId, GROUP).orElseThrow().getConsumerOwner());
    }

    @Test
    @DisplayName("should_computeLatency_When_backlog：10s 前的未应用行 → 延迟约 10000ms")
    void should_computeLatency_When_backlog() {
        ChangeEvent stale = new ChangeEvent(idGenerator.nextId(), taskId, 1L, GROUP, 1L, 1L,
                Instant.now().minusSeconds(10L), com.dbsync.common.enums.ChangeOp.UPDATE,
                List.of(Long.valueOf(1L)), null, java.util.Map.of("ID", Long.valueOf(1L)), null,
                com.dbsync.common.enums.ChangeStatus.NEW, 0, null, null);
        changeLogDao.insert(stale, "t", "inst");

        long latency = store.latencyP95(taskId);

        assertTrue(latency >= 9_000L && latency < 15_000L, "延迟应在 10s 附近，实际 " + latency);
    }

    @Test
    @DisplayName("should_zeroLatency_When_noBacklog：无积压时延迟为 0")
    void should_zeroLatency_When_noBacklog() {
        assertEquals(0L, store.latencyP95(taskId));
    }

    @Test
    @DisplayName("should_acquireAnyFreeGroup_When_poolEmpty：能按需领到空闲组")
    void should_acquireAnyFreeGroup_When_poolEmpty() {
        store.initializeGroups(taskId, 4);
        Optional<Integer> group = store.acquireAnyGroup(taskId, "slot#0", Duration.ofSeconds(30L));

        assertTrue(group.isPresent(), "空闲组应能被领到");
        assertTrue(group.get().intValue() >= 0);
    }

    /**
     * 短睡（等待租约过期）。
     *
     * @param millis 毫秒
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
