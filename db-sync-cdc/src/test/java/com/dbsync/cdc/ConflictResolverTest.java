package com.dbsync.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.enums.ChangeOp;
import com.dbsync.common.enums.ConflictPolicy;
import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.model.ChangeEvent;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.PrimaryKeyMeta;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.util.HashUtil;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T30 验收：三策略 × 三操作 = 9 个用例（+ 跳过时记录 conflict_action）。
 */
class ConflictResolverTest extends CdcTestSupport {

    private ConflictResolver resolver;
    private TableMeta table;
    private long taskId;
    private TableMapping mapping;

    /**
     * 建目标表（带 update_time，供 TIMESTAMP_NEWER 使用）。
     */
    @BeforeEach
    void setUpResolver() {
        exec(targetDs, "DROP TABLE IF EXISTS t_cf",
                "CREATE TABLE t_cf (id BIGINT NOT NULL PRIMARY KEY, name VARCHAR(64), "
                        + "update_time TIMESTAMP)");
        resolver = new ConflictResolver(dialect);
        table = metaWithTs("t_cf");
        taskId = newTask(16, 4, IncrementalMode.TRIGGER_LOG).getId();
        mapping = newMapping(taskId, "t_cf");
    }

    /**
     * 带时间列的表元数据。
     *
     * @param name 表名
     * @return 元数据
     */
    private TableMeta metaWithTs(String name) {
        ColumnMeta id = ColumnMeta.builder("id", 0, "BIGINT")
                .jdbcType(java.sql.Types.BIGINT).build();
        ColumnMeta name2 = ColumnMeta.builder("name", 1, "VARCHAR")
                .jdbcType(java.sql.Types.VARCHAR).size(64).build();
        ColumnMeta ts = ColumnMeta.builder("update_time", 2, "TIMESTAMP")
                .jdbcType(java.sql.Types.TIMESTAMP).build();
        return new TableMeta("PUBLIC", name, List.of(id, name2, ts),
                new PrimaryKeyMeta("pk", List.of("id")), List.of(), 10L, null);
    }

    /**
     * 造事件。
     *
     * @param op      操作
     * @param pk      主键
     * @param commitTs 提交时间
     * @return 事件
     */
    private ChangeEvent event(ChangeOp op, long pk, Instant commitTs) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("ID", Long.valueOf(pk));
        after.put("NAME", "n" + pk);
        after.put("UPDATE_TIME", Timestamp.from(commitTs));
        List<Object> pkTuple = List.of(Long.valueOf(pk));
        return new ChangeEvent(idGenerator.nextId(), taskId, mapping.getId(), 0,
                HashUtil.pkHash(pkTuple), 1L, commitTs, op, pkTuple, null,
                op == ChangeOp.DELETE ? null : after, null,
                com.dbsync.common.enums.ChangeStatus.NEW, 0, null, null);
    }

    /**
     * 插一行目标数据。
     *
     * @param pk 主键
     * @param ts 更新时间
     */
    private void insertTarget(long pk, Instant ts) {
        try (Connection connection = targetDs.getConnection();
             java.sql.PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO t_cf (id,name,update_time) VALUES (?,?,?)")) {
            ps.setLong(1, pk);
            ps.setString(2, "target");
            ps.setTimestamp(3, Timestamp.from(ts));
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("SOURCE_WINS × INSERT：总是应用")
    void should_applyInsert_When_sourceWins() throws Exception {
        try (Connection connection = targetDs.getConnection()) {
            ConflictDecision decision = resolver.resolve(event(ChangeOp.INSERT, 1L, Instant.now()),
                    connection, table, ConflictPolicy.SOURCE_WINS);
            assertTrue(decision.isApply());
            assertEquals(ConflictDecision.APPLY, decision.getAction());
        }
    }

    @Test
    @DisplayName("SOURCE_WINS × UPDATE：总是应用")
    void should_applyUpdate_When_sourceWins() throws Exception {
        try (Connection connection = targetDs.getConnection()) {
            assertTrue(resolver.resolve(event(ChangeOp.UPDATE, 1L, Instant.now()), connection,
                    table, ConflictPolicy.SOURCE_WINS).isApply());
        }
    }

    @Test
    @DisplayName("SOURCE_WINS × DELETE：总是应用")
    void should_applyDelete_When_sourceWins() throws Exception {
        try (Connection connection = targetDs.getConnection()) {
            assertTrue(resolver.resolve(event(ChangeOp.DELETE, 1L, Instant.now()), connection,
                    table, ConflictPolicy.SOURCE_WINS).isApply());
        }
    }

    @Test
    @DisplayName("TARGET_WINS × INSERT（目标不存在）：应用")
    void should_applyInsert_When_targetWinsAndAbsent() throws Exception {
        try (Connection connection = targetDs.getConnection()) {
            assertTrue(resolver.resolve(event(ChangeOp.INSERT, 2L, Instant.now()), connection,
                    table, ConflictPolicy.TARGET_WINS).isApply());
        }
    }

    @Test
    @DisplayName("TARGET_WINS × INSERT（目标已存在）：跳过并记录 TARGET_WINS_IGNORED")
    void should_recordConflictAction_When_skipped() throws Exception {
        insertTarget(3L, Instant.now());
        try (Connection connection = targetDs.getConnection()) {
            ConflictDecision decision = resolver.resolve(
                    event(ChangeOp.INSERT, 3L, Instant.now()), connection, table,
                    ConflictPolicy.TARGET_WINS);
            assertFalse(decision.isApply());
            assertEquals(ConflictDecision.TARGET_WINS_IGNORED, decision.getAction());
        }
    }

    @Test
    @DisplayName("TARGET_WINS × UPDATE：一律跳过")
    void should_skipUpdate_When_targetWins() throws Exception {
        insertTarget(4L, Instant.now());
        try (Connection connection = targetDs.getConnection()) {
            ConflictDecision decision = resolver.resolve(
                    event(ChangeOp.UPDATE, 4L, Instant.now()), connection, table,
                    ConflictPolicy.TARGET_WINS);
            assertFalse(decision.isApply());
            assertEquals(ConflictDecision.TARGET_WINS_IGNORED, decision.getAction());
        }
    }

    @Test
    @DisplayName("TARGET_WINS × DELETE：一律跳过")
    void should_skipDelete_When_targetWins() throws Exception {
        insertTarget(5L, Instant.now());
        try (Connection connection = targetDs.getConnection()) {
            assertFalse(resolver.resolve(event(ChangeOp.DELETE, 5L, Instant.now()), connection,
                    table, ConflictPolicy.TARGET_WINS).isApply());
        }
    }

    @Test
    @DisplayName("TIMESTAMP_NEWER × INSERT：总是应用")
    void should_applyInsert_When_timestampNewer() throws Exception {
        try (Connection connection = targetDs.getConnection()) {
            assertTrue(resolver.resolve(event(ChangeOp.INSERT, 6L, Instant.now()), connection,
                    table, ConflictPolicy.TIMESTAMP_NEWER).isApply());
        }
    }

    @Test
    @DisplayName("TIMESTAMP_NEWER × UPDATE（源更新）：应用")
    void should_applyUpdate_When_sourceIsNewer() throws Exception {
        Instant older = Instant.now().minusSeconds(60L);
        insertTarget(7L, older);
        try (Connection connection = targetDs.getConnection()) {
            assertTrue(resolver.resolve(event(ChangeOp.UPDATE, 7L, Instant.now()), connection,
                    table, ConflictPolicy.TIMESTAMP_NEWER).isApply());
        }
    }

    @Test
    @DisplayName("TIMESTAMP_NEWER × UPDATE（源更旧）：跳过")
    void should_skipUpdate_When_sourceIsOlder() throws Exception {
        insertTarget(8L, Instant.now());
        try (Connection connection = targetDs.getConnection()) {
            ConflictDecision decision = resolver.resolve(
                    event(ChangeOp.UPDATE, 8L, Instant.now().minusSeconds(60L)), connection, table,
                    ConflictPolicy.TIMESTAMP_NEWER);
            assertFalse(decision.isApply());
            assertEquals(ConflictDecision.TIMESTAMP_OLDER_IGNORED, decision.getAction());
        }
    }

    @Test
    @DisplayName("TIMESTAMP_NEWER × DELETE（源更旧）：跳过")
    void should_skipDelete_When_sourceIsOlder() throws Exception {
        insertTarget(9L, Instant.now());
        try (Connection connection = targetDs.getConnection()) {
            assertFalse(resolver.resolve(
                    event(ChangeOp.DELETE, 9L, Instant.now().minusSeconds(60L)), connection, table,
                    ConflictPolicy.TIMESTAMP_NEWER).isApply());
        }
    }

    @Test
    @DisplayName("TIMESTAMP_NEWER 降级：目标表无时间列时直接应用并告警")
    void should_apply_When_targetHasNoTimestampColumn() throws Exception {
        createTargetTable("t_cf2");
        TableMeta plain = tableMeta("t_cf2");
        try (Connection connection = targetDs.getConnection()) {
            assertTrue(resolver.resolve(event(ChangeOp.UPDATE, 10L, Instant.EPOCH), connection,
                    plain, ConflictPolicy.TIMESTAMP_NEWER).isApply(),
                    "无法判定时应应用（丢数据比覆盖更难发现）");
        }
    }
}
