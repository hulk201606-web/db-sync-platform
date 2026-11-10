package com.dbsync.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dbsync.common.enums.ChangeStatus;
import com.dbsync.common.enums.ConflictPolicy;
import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.ChangeEvent;
import com.dbsync.common.model.DeadLetter;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.util.RetryTemplate;
import com.dbsync.dialect.Dialect;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * T29 验收：组内保序（CDC-05）、组间隔离、毒消息隔离、markApplied 先于 advance。
 */
class OrderingTest extends CdcTestSupport {

    private long taskId;

    /**
     * 建任务。
     */
    @BeforeEach
    void setUpOrdering() {
        taskId = newTask(4, 2, IncrementalMode.TRIGGER_LOG).getId();
    }

    /**
     * 装配上下文。
     *
     * @param mapping 表映射
     * @param table   目标表名
     * @param applier 应用器（null 用默认）
     * @return 上下文
     */
    private CdcContext context(TableMapping mapping, String table, UpsertApplier applier) {
        Map<Long, TableMeta> tables = new LinkedHashMap<>();
        tables.put(Long.valueOf(mapping.getId()), tableMeta(table));
        return CdcContext.builder()
                .taskId(taskId).groupCount(4).consumerConcurrency(1).owner("order-test")
                .queue(new ChangeQueue(changeLogDao))
                .watermarks(new WatermarkStore(watermarkDao, changeLogDao))
                .resolver(new ConflictResolver(dialect))
                .applier(applier)
                .dialect(dialect)
                .tables(tables)
                .policy(ConflictPolicy.SOURCE_WINS)
                .batchSize(100)
                .retry(new RetryTemplate(3, Duration.ofMillis(1L), 1.0d, 0.0d))
                .deadLetters(letter -> deadLetterDao.insert(copy(letter)))
                .targetConnections(targetDs::getConnection)
                .pollInterval(Duration.ofMillis(5L))
                .build();
    }

    /**
     * 复制死信（{@code DeadLetterService} 负责分配 ID，这里手工分配）。
     *
     * @param letter 死信
     * @return 带 ID 的死信
     */
    private DeadLetter copy(DeadLetter letter) {
        return new DeadLetter(idGenerator.nextId(), letter.getTaskId(),
                letter.getTableMappingId(), letter.getSourceType(), letter.getRefId(),
                letter.getPkJson(), letter.getPayloadJson(), letter.getErrorCode(),
                letter.getErrorMsg(), letter.getStackDigest(), letter.getStatus(),
                letter.getRetry());
    }

    @Test
    @DisplayName("should_endWithLatestValue_When_1000IncrementsOutOfOrderInsert：最终值=第 1000 次")
    void should_endWithLatestValue_When_1000IncrementsOutOfOrderInsert() {
        createTargetTable("t_ord");
        TableMapping mapping = newMapping(taskId, "t_ord");
        int total = 1000;
        List<Integer> seqs = new ArrayList<>();
        for (int i = 1; i <= total; i++) {
            seqs.add(Integer.valueOf(i));
        }
        // 打乱的是"入队顺序"，seq 仍是 1..1000
        Collections.shuffle(seqs, new Random(2024L));
        for (Integer seq : seqs) {
            changeLogDao.insert(change(taskId, mapping, 42L, "n" + seq, seq.longValue(), 0),
                    "trace", "inst");
        }

        int processed = drainAll(context(mapping, "t_ord", null), 0, 50);

        assertEquals(total, processed, "必须全部处理");
        assertEquals("n1000", nameOf("t_ord", 42L),
                "组内严格按 seq 升序处理，最终值必须是 seq 最大的那次（CDC-05）");
        assertEquals(1L, countRows("t_ord"));
    }

    @Test
    @DisplayName("should_isolateGroups_When_oneGroupHasBadRow：组间互不影响")
    void should_isolateGroups_When_oneGroupHasBadRow() {
        createTargetTable("t_ord2");
        TableMapping mapping = newMapping(taskId, "t_ord2");
        changeLogDao.insert(change(taskId, mapping, 1L, "g1", 1L, 1), "t", "i");
        changeLogDao.insert(change(taskId, mapping, 2L, "g2", 2L, 2), "t", "i");

        drainAll(context(mapping, "t_ord2", null), 2, 10);

        assertEquals("g2", nameOf("t_ord2", 2L), "组 2 必须正常消费完成");
        assertEquals(1L, countRows("t_ord2"), "组 2 只处理自己的行");
    }

    @Test
    @DisplayName("should_markDeadAndContinue_When_retryExhausted：毒消息转死信后继续处理")
    void should_markDeadAndContinue_When_retryExhausted() {
        createTargetTable("t_ord3");
        TableMapping mapping = newMapping(taskId, "t_ord3");
        changeLogDao.insert(change(taskId, mapping, 1L, "poison", 1L, 0), "t", "i");
        changeLogDao.insert(change(taskId, mapping, 2L, "good", 2L, 0), "t", "i");

        UpsertApplier poison = new UpsertApplier() {
            @Override
            public ApplyResult apply(Connection connection, Dialect dialect, TableMeta table,
                                     ChangeEvent event) {
                if ("poison".equals(event.getAfter().get("NAME"))) {
                    throw new DbSyncException(ErrorCode.CHANGE_APPLY_FAILED, "simulated poison");
                }
                return super.apply(connection, dialect, table, event);
            }
        };

        drainAll(context(mapping, "t_ord3", poison), 0, 10);

        assertEquals("good", nameOf("t_ord3", 2L), "毒消息之后的变更必须仍被处理");
        assertEquals(1L, deadLetterDao.countOpen(taskId), "毒消息必须进死信（1 条）");
        assertEquals(1L, changeLogDao.countByStatus(taskId, ChangeStatus.DEAD));
    }

    @Test
    @DisplayName("should_callMarkAppliedBeforeAdvance_When_batchDone：先标记再推水位（InOrder）")
    void should_callMarkAppliedBeforeAdvance_When_batchDone() {
        createTargetTable("t_ord");
        TableMapping mapping = newMapping(taskId, "t_ord");
        ChangeQueue queue = mock(ChangeQueue.class);
        WatermarkStore watermarks = mock(WatermarkStore.class);
        ChangeEvent event = change(taskId, mapping, 1L, "x", 1L, 0);

        CdcContext ctx = CdcContext.builder()
                .taskId(taskId).groupCount(4).owner("order-test")
                .queue(queue).watermarks(watermarks)
                .resolver(new ConflictResolver(dialect))
                .applier(new UpsertApplier())
                .dialect(dialect)
                .tables(Map.of(Long.valueOf(mapping.getId()), tableMeta("t_ord")))
                .policy(ConflictPolicy.SOURCE_WINS)
                .retry(new RetryTemplate(1, Duration.ofMillis(1L), 1.0d, 0.0d))
                .targetConnections(targetDs::getConnection)
                .pollInterval(Duration.ofMillis(1L))
                .build();

        new GroupWorker(ctx, 0).drain(List.of(event));

        InOrder order = inOrder(queue, watermarks);
        order.verify(queue).markApplied(any());
        order.verify(watermarks).advance(anyLong(), anyInt(), anyLong(), any());
    }

    @Test
    @DisplayName("should_keepSeqOrder_When_batchMixedOps：批内也保序（update 后 delete）")
    void should_keepSeqOrder_When_batchMixedOps() {
        createTargetTable("t_ord4");
        TableMapping mapping = newMapping(taskId, "t_ord4");
        changeLogDao.insert(change(taskId, mapping, 7L, "v", 1L, 0), "t", "i");
        changeLogDao.insert(deleteChange(taskId, mapping, 7L, 2L, 0), "t", "i");

        drainAll(context(mapping, "t_ord4", null), 0, 10);

        assertEquals(0L, countRows("t_ord4"), "先 update 再 delete，最终不应有行");
        assertEquals(2L, changeLogDao.countByStatus(taskId, ChangeStatus.APPLIED));
    }

    @Test
    @DisplayName("should_notApply_When_groupIsEmpty：空组不产生任何副作用")
    void should_notApply_When_groupIsEmpty() {
        createTargetTable("t_ord5");
        TableMapping mapping = newMapping(taskId, "t_ord5");
        changeLogDao.insert(change(taskId, mapping, 1L, "x", 1L, 0), "t", "i");

        int processed = drainAll(context(mapping, "t_ord5", null), 3, 5);

        assertEquals(0, processed);
        assertEquals(0L, countRows("t_ord5"));
        assertTrue(changeLogDao.countByStatus(taskId, ChangeStatus.APPLIED) == 0L);
    }
}
