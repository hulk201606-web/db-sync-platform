package com.dbsync.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.enums.DeadLetterSourceType;
import com.dbsync.common.enums.DeadLetterStatus;
import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.enums.ShardState;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.model.DeadLetter;
import com.dbsync.common.model.Shard;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T26 验收：死信创建 / 幂等重放 / 带理由丢弃 / 计数。
 *
 * <p>幂等性用"真数据"验证：重放器把载荷 upsert 进 H2 目标表，
 * 重放两次后目标表仍然只有 1 行 —— 这是唯一能证明"运维连点两次不会写脏数据"的方式。
 */
class DeadLetterServiceTest extends MigrationTestSupport {

    private static final long TASK_ID = 6_000L;
    private static final long MAPPING_ID = 6_100L;

    private DeadLetterService service;

    /**
     * 目标表 + 幂等重放器。
     */
    @BeforeEach
    void setUpService() {
        exec(targetDs, "DROP TABLE IF EXISTS t_dl",
                "CREATE TABLE t_dl (id BIGINT NOT NULL PRIMARY KEY, name VARCHAR(64))");
        service = new DeadLetterService(deadLetterDao, shardDao, this::applyIdempotent);
    }

    /**
     * 幂等应用：先 UPDATE，未命中再 INSERT。
     *
     * <p>生产实现由 {@code db-sync-cdc} 用方言 upsert SQL 完成；
     * 这里用"UPDATE→INSERT"表达同一语义，避免把方言细节搬进测试。
     *
     * @param letter 死信
     * @return 影响行数
     */
    private int applyIdempotent(DeadLetter letter) {
        try (Connection connection = targetDs.getConnection()) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE t_dl SET name=? WHERE id=?")) {
                update.setString(1, "n1");
                update.setLong(2, 1L);
                if (update.executeUpdate() > 0) {
                    return 1;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO t_dl (id,name) VALUES (?,?)")) {
                insert.setLong(1, 1L);
                insert.setString(2, "n1");
                return insert.executeUpdate();
            }
        } catch (SQLException e) {
            throw new DbSyncException(com.dbsync.common.exception.ErrorCode.CHANGE_APPLY_FAILED,
                    e, "重放失败：" + e.getMessage());
        }
    }

    /**
     * 造一条 CHANGE 死信并落库。
     *
     * @return 死信 ID
     */
    private long newChangeLetter() {
        service.create(new DeadLetter(0L, TASK_ID, MAPPING_ID, DeadLetterSourceType.CHANGE, 123L,
                "{\"id\":1}", "{\"id\":1,\"name\":\"n1\"}", "E4010", "apply failed", "abc",
                DeadLetterStatus.OPEN, 0));
        return deadLetterDao.list(TASK_ID, DeadLetterStatus.OPEN, 10).get(0).getId();
    }

    @Test
    @DisplayName("should_beIdempotent_When_replayTwice：重放两次目标行仍 1 行")
    void should_beIdempotent_When_replayTwice() {
        long id = newChangeLetter();

        ReplayResult first = service.replay(id, "admin");
        ReplayResult second = service.replay(id, "admin");

        assertTrue(first.isApplied(), "首次重放应真的写数据");
        assertFalse(second.isApplied(), "第二次重放必须短路，不能再写一次");
        assertTrue(second.isDuplicate(), second.getMessage());
        assertEquals(DeadLetterStatus.REPLAYED, second.getStatus());
        assertEquals(1L, countRows(targetDs, "t_dl"), "目标表必须仍然只有 1 行");
        assertEquals(0L, service.countOpen(TASK_ID), "重放成功后不再有 OPEN 死信");
    }

    @Test
    @DisplayName("should_reopenLetter_When_applierFails：重放失败退回 OPEN 并累计次数")
    void should_reopenLetter_When_applierFails() {
        DeadLetterService failing = new DeadLetterService(deadLetterDao, shardDao, letter -> {
            throw new DbSyncException(com.dbsync.common.exception.ErrorCode.CHANGE_APPLY_FAILED,
                    "目标库拒绝写入");
        });
        failing.create(new DeadLetter(0L, TASK_ID, MAPPING_ID, DeadLetterSourceType.CHANGE, 1L,
                "{\"id\":1}", "{}", "E4010", "x", "d", DeadLetterStatus.OPEN, 0));
        long id = deadLetterDao.list(TASK_ID, DeadLetterStatus.OPEN, 10).get(0).getId();

        ReplayResult result = failing.replay(id, "admin");

        assertFalse(result.isApplied());
        assertEquals(DeadLetterStatus.OPEN, result.getStatus());
        assertEquals(1L, service.countOpen(TASK_ID));
        assertTrue(deadLetterDao.findById(id).orElseThrow().getRetry() >= 1, "重试次数应累计");
    }

    @Test
    @DisplayName("should_rejectDiscard_When_reasonBlank：丢弃必须给理由")
    void should_rejectDiscard_When_reasonBlank() {
        long id = newChangeLetter();

        DbSyncException e = assertThrows(DbSyncException.class,
                () -> service.discard(id, "admin", "  "));
        assertTrue(e.getMessage().contains("理由"), e.getMessage());
        assertEquals(DeadLetterStatus.OPEN, deadLetterDao.findById(id).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("should_rejectDiscard_When_alreadyReplayed：终态不可再丢弃")
    void should_rejectDiscard_When_alreadyReplayed() {
        long id = newChangeLetter();
        service.replay(id, "admin");

        assertThrows(DbSyncException.class, () -> service.discard(id, "admin", "误报"));
        assertEquals(DeadLetterStatus.REPLAYED,
                deadLetterDao.findById(id).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("should_markDiscarded_When_reasonGiven：带理由可丢弃")
    void should_markDiscarded_When_reasonGiven() {
        long id = newChangeLetter();

        service.discard(id, "admin", "源数据本身就是脏的，业务已确认");

        assertEquals(DeadLetterStatus.DISCARDED,
                deadLetterDao.findById(id).orElseThrow().getStatus());
        assertEquals(0L, service.countOpen(TASK_ID));
    }

    @Test
    @DisplayName("should_replayShardLetter_When_shardExists：SHARD 死信重放=分片放回 PENDING")
    void should_replayShardLetter_When_shardExists() {
        shardDao.insertBatch(List.of(new Shard(777L, TASK_ID, MAPPING_ID, 0, RangeKind.NUMERIC,
                null, null, ShardState.DEAD, null, null, 0L, 3, 1)));
        service.create(new DeadLetter(0L, TASK_ID, MAPPING_ID, DeadLetterSourceType.SHARD, 777L,
                "{\"shardNo\":0}", null, "E3010", "retry exhausted", "d",
                DeadLetterStatus.OPEN, 0));
        long id = deadLetterDao.list(TASK_ID, DeadLetterStatus.OPEN, 10).get(0).getId();

        ReplayResult result = service.replay(id, "admin");

        assertTrue(result.isApplied());
        assertEquals(ShardState.PENDING, shardDao.findById(777L).orElseThrow().getState(),
                "分片必须被放回 PENDING 以便重新调度");
        assertEquals(0L, service.countOpen(TASK_ID));
    }

    @Test
    @DisplayName("should_rejectReplay_When_letterMissing：不存在的死信给出明确错误")
    void should_rejectReplay_When_letterMissing() {
        assertThrows(DbSyncException.class, () -> service.replay(999_999L, "admin"));
    }

    @Test
    @DisplayName("should_computeDigest_When_exceptionGiven：同因异常得到同一指纹")
    void should_computeDigest_When_exceptionGiven() {
        String a = DeadLetterService.digestOf(
                new DbSyncException(com.dbsync.common.exception.ErrorCode.CHANGE_APPLY_FAILED, "x"));
        String b = DeadLetterService.digestOf(
                new DbSyncException(com.dbsync.common.exception.ErrorCode.CHANGE_APPLY_FAILED, "x"));
        String c = DeadLetterService.digestOf(
                new DbSyncException(com.dbsync.common.exception.ErrorCode.CHANGE_APPLY_FAILED, "y"));
        assertEquals(a, b);
        assertFalse(a.equals(c));
        assertEquals("0", DeadLetterService.digestOf(null));
    }
}
