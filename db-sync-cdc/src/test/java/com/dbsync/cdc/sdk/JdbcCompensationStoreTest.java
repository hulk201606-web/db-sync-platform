package com.dbsync.cdc.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.enums.DbType;
import com.dbsync.dialect.Dialect;
import com.dbsync.dialect.DialectRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JdbcCompensationStore} 验收：真实 H2 业务库上的建表、落库、待重放查询、
 * 条件状态迁移（PENDING→REPLAYED / DISCARDED / retry+1）。
 *
 * <p>H2 用 {@code MODE=MySQL} + MySqlDialect（与 CdcTestSupport / UpsertApplierTest
 * 已验证的组合一致），故建表 DDL、反引号、分页 SQL 均走真实方言实现；
 * 不连任何真实 MySQL / 端口。
 */
class JdbcCompensationStoreTest {

    private static final long TASK_ID = 88L;
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private JdbcDataSource ds;
    private JdbcCompensationStore store;

    @BeforeEach
    void setUp() {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sdk_store_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        ds.setUser("sa");
        ds.setPassword("");
        Dialect dialect = DialectRegistry.get(DbType.MYSQL);
        store = new JdbcCompensationStore(ds, dialect, null, CLOCK);
    }

    private static CompensationRecord pending(long taskId, String payload, Instant now) {
        return CompensationRecord.pending(0L, taskId, "orders",
                CompensationRecord.OP_UPDATE, "[1001]", payload, "boom", now);
    }

    @Test
    @DisplayName("ensureTable：在业务库建出 sync_sdk_outbox 且可重复调用（列契约齐全）")
    void ensureTableCreatesOutboxIdempotently() throws Exception {
        store.ensureTable();
        assertThat(tableExists(JdbcCompensationStore.TABLE_NAME)).isTrue();

        // 幂等：重复调用不应因"表/索引已存在"报错
        store.ensureTable();
        assertThat(tableExists(JdbcCompensationStore.TABLE_NAME)).isTrue();
        assertThat(columnsOf(JdbcCompensationStore.TABLE_NAME)).contains(
                "id", "task_id", "table_name", "op", "pk_json", "payload", "status",
                "retry", "last_error", "created_at", "next_retry_at", "replayed_at");
    }

    @Test
    @DisplayName("outboxMeta：表名/主键/索引契约固定（静态词表，防脚本漂移）")
    void outboxMetaKeepsContract() {
        assertThat(store.outboxMeta().getTableName()).isEqualTo(JdbcCompensationStore.TABLE_NAME);
        assertThat(store.outboxMeta().primaryKeyNames()).containsExactly("id");
        assertThat(store.outboxMeta().getIndexes()).isNotEmpty();
        assertThat(store.outboxMeta().getIndexes().get(0).getName())
                .isEqualTo(JdbcCompensationStore.INDEX_NAME);
    }

    @Test
    @DisplayName("record：返回自增主键，findById 可查回完整 PENDING 记录")
    void recordPersistsPendingAndReturnsId() {
        long id = store.record(pending(TASK_ID, "{\"id\":1001,\"name\":\"alice\"}", NOW));

        assertThat(id).isPositive();
        CompensationRecord loaded = store.findById(id);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getId()).isEqualTo(id);
        assertThat(loaded.getTaskId()).isEqualTo(TASK_ID);
        assertThat(loaded.getTableName()).isEqualTo("orders");
        assertThat(loaded.getOp()).isEqualTo(CompensationRecord.OP_UPDATE);
        assertThat(loaded.getStatus()).isEqualTo(CompensationStatus.PENDING);
        assertThat(loaded.getPkJson()).isEqualTo("[1001]");
        assertThat(loaded.getPayload()).contains("alice");
        assertThat(loaded.getRetry()).isZero();
        assertThat(store.count(TASK_ID, CompensationStatus.PENDING)).isEqualTo(1);
    }

    @Test
    @DisplayName("listPending：只返回到期、指定 task、PENDING 的记录，并尊重 limit")
    void listPendingFiltersByTaskStatusDueAndLimit() {
        long a = store.record(pending(TASK_ID, "{\"id\":1}", NOW));
        long b = store.record(pending(TASK_ID, "{\"id\":2}", NOW));
        long c = store.record(pending(TASK_ID, "{\"id\":3}", NOW));
        // 未到期：next_retry_at 在未来
        store.record(new CompensationRecord(0L, TASK_ID, "orders",
                CompensationRecord.OP_UPDATE, "[4]", "{\"id\":4}",
                CompensationStatus.PENDING, 0, "boom", NOW, NOW.plusSeconds(3600L), null));
        // 别的任务
        store.record(pending(TASK_ID + 1L, "{\"id\":5}", NOW));
        // 已重放（终态，不应再被查到）
        long replayedId = store.record(pending(TASK_ID, "{\"id\":6}", NOW));
        store.markReplayed(replayedId);

        List<CompensationRecord> all = store.listPending(TASK_ID, 100);
        assertThat(all).hasSize(3);
        assertThat(all).extracting(CompensationRecord::getId)
                .containsExactlyInAnyOrder(a, b, c);
        assertThat(all).allSatisfy(r -> {
            assertThat(r.getTaskId()).isEqualTo(TASK_ID);
            assertThat(r.getStatus()).isEqualTo(CompensationStatus.PENDING);
        });

        assertThat(store.listPending(TASK_ID, 2)).hasSize(2);
        assertThat(store.listPending(TASK_ID + 1L, 100)).hasSize(1);
    }

    @Test
    @DisplayName("markReplayed：条件 UPDATE 仅 PENDING→REPLAYED 一次，重复/不存在均 false")
    void markReplayedIsConditional() {
        long id = store.record(pending(TASK_ID, "{\"id\":9}", NOW));

        assertThat(store.markReplayed(id)).isTrue();
        CompensationRecord loaded = store.findById(id);
        assertThat(loaded.getStatus()).isEqualTo(CompensationStatus.REPLAYED);
        assertThat(loaded.getReplayedAt()).isNotNull();

        assertThat(store.markReplayed(id)).isFalse();
        assertThat(store.markReplayed(id + 999_999L)).isFalse();
        assertThat(store.count(TASK_ID, CompensationStatus.PENDING)).isZero();
        assertThat(store.count(TASK_ID, CompensationStatus.REPLAYED)).isEqualTo(1);
    }

    @Test
    @DisplayName("markDiscarded / markRetry：终态与退避字段都落在条件 UPDATE 上")
    void markDiscardedAndRetry() {
        long discardedId = store.record(pending(TASK_ID, "{\"id\":10}", NOW));
        assertThat(store.markDiscarded(discardedId, "人工放弃")).isTrue();
        assertThat(store.findById(discardedId).getStatus())
                .isEqualTo(CompensationStatus.DISCARDED);
        // 终态不可再被 markReplayed 抢回
        assertThat(store.markReplayed(discardedId)).isFalse();

        long retryId = store.record(pending(TASK_ID, "{\"id\":11}", NOW));
        assertThat(store.markRetry(retryId, "still down", NOW.plusSeconds(60L))).isTrue();
        CompensationRecord retried = store.findById(retryId);
        assertThat(retried.getRetry()).isEqualTo(1);
        assertThat(retried.getLastError()).isEqualTo("still down");
        assertThat(retried.getStatus()).isEqualTo(CompensationStatus.PENDING);
    }

    private boolean tableExists(String table) throws Exception {
        try (Connection connection = ds.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT COUNT(1) FROM INFORMATION_SCHEMA.TABLES "
                             + "WHERE UPPER(TABLE_NAME) = ?")) {
            ps.setString(1, table.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0L;
            }
        }
    }

    private List<String> columnsOf(String table) throws Exception {
        List<String> columns = new ArrayList<>();
        try (Connection connection = ds.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                             + "WHERE UPPER(TABLE_NAME) = ?")) {
            ps.setString(1, table.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return columns;
    }
}
