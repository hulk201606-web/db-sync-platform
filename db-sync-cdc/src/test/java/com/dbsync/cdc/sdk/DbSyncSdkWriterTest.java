package com.dbsync.cdc.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dbsync.common.enums.DbType;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.dialect.Dialect;
import com.dbsync.dialect.DialectRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link DbSyncSdkWriter} 验收：STRICT/ASYNC 一致性模式、失败补偿、补偿端口自身失败兜底、
 * {@code replayPending} 重放幂等，以及"业务写入与重放并发"路径。
 *
 * <p>补偿表走真实 H2（{@code MODE=MySQL} + MySqlDialect，与 CdcTestSupport 同口径）；
 * 目标端用内存 fake（按主键 upsert 的"目标表"），因此不依赖任何真实端口/SMTP/MySQL。
 * 重试退避用 no-op Sleeper、时间用固定 Clock：测试不真睡、不依赖墙钟。
 */
class DbSyncSdkWriterTest {

    private static final long TASK_ID = 77L;
    private static final String TABLE = "orders";
    private static final Map<String, List<String>> PK = Map.of(TABLE, List.of("id"));
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private JdbcCompensationStore store;
    private RecordingTarget delegate;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sdk_writer_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        ds.setUser("sa");
        ds.setPassword("");
        Dialect dialect = DialectRegistry.get(DbType.MYSQL);
        store = new JdbcCompensationStore(ds, dialect, null, CLOCK);
        store.ensureTable();
        delegate = new RecordingTarget();
    }

    /** 构造被测 writer（重试 2 次、退避交给 no-op Sleeper、固定时钟）。 */
    private DbSyncSdkWriter writer(SdkWriteMode mode, CompensationStore compensationStore) {
        return new DbSyncSdkWriter(delegate, compensationStore, TASK_ID, PK, mode,
                2, 1L, 1L, millis -> { }, CLOCK);
    }

    @Test
    @DisplayName("STRICT：重试耗尽抛 SdkWriteException，异常内容含表名与主键")
    void strictExceptionCarriesTableAndPk() {
        DbSyncSdkWriter w = writer(SdkWriteMode.STRICT, store);
        delegate.fail();

        SdkWriteException ex = assertThrows(SdkWriteException.class,
                () -> w.writeOutcome(TABLE, Map.of("id", 1001L, "name", "alice")));

        assertThat(ex.getMessage()).contains(TABLE).contains("1001");
        assertThat(ex.getTable()).isEqualTo(TABLE);
        assertThat(ex.getPkTuple()).extracting(String::valueOf).containsExactly("1001");
        assertThat(ex.getTaskId()).isEqualTo(TASK_ID);
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.RETRY_EXHAUSTED);
        assertThat(ex.getCause()).isNotNull();
    }

    @Test
    @DisplayName("ASYNC：失败不抛，补偿表新增一条 PENDING")
    void asyncDoesNotThrowAndRecordsPending() {
        DbSyncSdkWriter w = writer(SdkWriteMode.ASYNC, store);
        delegate.fail();
        long before = store.count(TASK_ID, CompensationStatus.PENDING);

        assertThatCode(() -> {
            WriteOutcome outcome = w.writeOutcome(TABLE,
                    Map.of("id", 1002L, "name", "bob"));
            assertThat(outcome).isEqualTo(WriteOutcome.COMPENSATED);
        }).doesNotThrowAnyException();

        assertThat(store.count(TASK_ID, CompensationStatus.PENDING)).isEqualTo(before + 1);
        assertThat(w.compensatedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("ASYNC：补偿 store 自身失败仍不抛，返回 DROPPED")
    void asyncStillDoesNotThrowWhenStoreFails() {
        DbSyncSdkWriter w = writer(SdkWriteMode.ASYNC, new FailingCompensationStore());
        delegate.fail();

        assertThatCode(() -> {
            WriteOutcome outcome = w.writeOutcome(TABLE,
                    Map.of("id", 1003L, "name", "carol"));
            assertThat(outcome).isEqualTo(WriteOutcome.DROPPED);
        }).doesNotThrowAnyException();

        assertThat(w.droppedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("replayPending：重放两次目标表仍 1 行（幂等）")
    void replayIsIdempotent() {
        DbSyncSdkWriter w = writer(SdkWriteMode.ASYNC, store);
        delegate.fail();
        w.writeOutcome(TABLE, Map.of("id", 1004L, "name", "dave"));
        assertThat(store.count(TASK_ID, CompensationStatus.PENDING)).isEqualTo(1);

        delegate.recover();
        int first = w.replayPending(TASK_ID, 10);
        int second = w.replayPending(TASK_ID, 10);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(delegate.rowCount()).isEqualTo(1);
        assertThat(store.count(TASK_ID, CompensationStatus.REPLAYED)).isEqualTo(1);
        assertThat(store.count(TASK_ID, CompensationStatus.PENDING)).isZero();
        assertThat(w.replaySuccessCount()).isEqualTo(1);
    }

    @Test
    @Timeout(15)
    @DisplayName("并发：业务写入与重放同时进行不抛异常，目标表收敛为 1 行")
    void concurrentWriteAndReplay() throws Exception {
        DbSyncSdkWriter w = writer(SdkWriteMode.ASYNC, store);
        delegate.fail();
        w.writeOutcome(TABLE, Map.of("id", 1005L, "name", "init"));
        delegate.recover();

        int writers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < writers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int n = 0; n < 20; n++) {
                        assertThat(w.writeOutcome(TABLE,
                                Map.of("id", 1005L, "name", "v" + n)))
                                .isEqualTo(WriteOutcome.APPLIED);
                    }
                    return null;
                }));
            }
            futures.add(pool.submit(() -> {
                start.await();
                for (int n = 0; n < 20; n++) {
                    w.replayPending(TASK_ID, 10);
                }
                return null;
            }));
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(delegate.rowCount()).isEqualTo(1);
        assertThat(store.count(TASK_ID, CompensationStatus.REPLAYED)).isEqualTo(1);
    }

    /** 内存"目标表"：按主键 upsert，故同一变更重放多次仍只有一行。 */
    private static final class RecordingTarget implements DbSyncWriter {

        private final AtomicBoolean failing = new AtomicBoolean(false);
        private final ConcurrentHashMap<String, Map<String, Object>> rows =
                new ConcurrentHashMap<>();

        void fail() {
            failing.set(true);
        }

        void recover() {
            failing.set(false);
        }

        @Override
        public long write(String table, Map<String, Object> row) {
            if (failing.get()) {
                throw new IllegalStateException("target unavailable");
            }
            rows.put(primaryKey(row), new LinkedHashMap<>(row));
            return 1L;
        }

        @Override
        public long delete(String table, List<Object> pkTuple) {
            if (failing.get()) {
                throw new IllegalStateException("target unavailable");
            }
            if (!pkTuple.isEmpty()) {
                rows.remove(String.valueOf(pkTuple.get(0)));
            }
            return 1L;
        }

        @Override
        public void flush() {
            // fake 目标端无缓冲，flush 为空操作（重放路径会调用它）
        }

        @Override
        public boolean isHealthy() {
            return !failing.get();
        }

        int rowCount() {
            return rows.size();
        }

        private static String primaryKey(Map<String, Object> row) {
            Object id = row.get("id");
            if (id == null) {
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    if ("id".equalsIgnoreCase(entry.getKey())) {
                        id = entry.getValue();
                        break;
                    }
                }
            }
            return String.valueOf(id);
        }
    }

    /** 故意在 record 抛异常的补偿端口，验证"补偿也失败仍不抛给业务方"。 */
    private static final class FailingCompensationStore implements CompensationStore {

        @Override
        public long record(CompensationRecord record) {
            throw new IllegalStateException("compensation store down");
        }

        @Override
        public List<CompensationRecord> listPending(long taskId, int limit) {
            return List.of();
        }

        @Override
        public boolean markReplayed(long id) {
            return false;
        }

        @Override
        public boolean markDiscarded(long id, String reason) {
            return false;
        }

        @Override
        public boolean markRetry(long id, String lastError, Instant nextRetryAt) {
            return false;
        }
    }
}
