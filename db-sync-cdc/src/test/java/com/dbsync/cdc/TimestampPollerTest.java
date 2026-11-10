package com.dbsync.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.model.ChangeEvent;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.PrimaryKeyMeta;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.model.TableMeta;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T31 验收：时间戳模式在"时钟回拨"下不丢数据（重叠窗口 + 主键去重）。
 */
class TimestampPollerTest extends CdcTestSupport {

    private TimestampPoller poller;
    private JdbcDataSource sourceDs;
    private long taskId;
    private TableMapping mapping;
    private TableMeta table;
    private ColumnMeta tsColumn;

    /**
     * 建源表（含 update_time）。
     */
    @BeforeEach
    void setUpPoller() {
        sourceDs = h2(metaName() + "_src");
        // H2 内存库在同一个测试类内共享（DB_CLOSE_DELAY=-1），必须显式清理
        exec(sourceDs, "DROP TABLE IF EXISTS t_ts",
                "CREATE TABLE t_ts (id BIGINT NOT NULL PRIMARY KEY, name VARCHAR(64), "
                        + "update_time TIMESTAMP)");
        poller = new TimestampPoller(null, null, null, null, 100);
        taskId = newTask(4, 2, IncrementalMode.TIMESTAMP_VERSION).getId();
        mapping = newMapping(taskId, "t_ts");
        table = tsTableMeta();
        tsColumn = TimestampPoller.timestampColumn(table);
    }

    /**
     * 带时间列的表元数据。
     *
     * @return 元数据
     */
    private TableMeta tsTableMeta() {
        ColumnMeta id = ColumnMeta.builder("id", 0, "BIGINT")
                .jdbcType(java.sql.Types.BIGINT).build();
        ColumnMeta name = ColumnMeta.builder("name", 1, "VARCHAR")
                .jdbcType(java.sql.Types.VARCHAR).size(64).build();
        ColumnMeta ts = ColumnMeta.builder("update_time", 2, "TIMESTAMP")
                .jdbcType(java.sql.Types.TIMESTAMP).build();
        return new TableMeta("PUBLIC", "t_ts", List.of(id, name, ts),
                new PrimaryKeyMeta("pk_ts", List.of("id")), List.of(), 10L, null);
    }

    /**
     * 插一行。
     *
     * @param id   主键
     * @param name 值
     * @param ts   更新时间
     */
    private void insert(long id, String name, Instant ts) {
        try (Connection connection = sourceDs.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO t_ts (id,name,update_time) VALUES (?,?,?)")) {
            ps.setLong(1, id);
            ps.setString(2, name);
            ps.setTimestamp(3, Timestamp.from(ts));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 走一次读取。
     *
     * @param from 起始时间
     * @return 事件
     * @throws SQLException 读取失败
     */
    private List<ChangeEvent> read(Instant from) throws SQLException {
        try (Connection connection = sourceDs.getConnection()) {
            return poller.read(connection, dialect, mapping, table, tsColumn, from, 4,
                    Long.valueOf(mapping.getId()));
        }
    }

    @Test
    @DisplayName("should_notLoseData_When_clockRolledBack：回拨到更早时间戳的行也不丢")
    void should_notLoseData_When_clockRolledBack() throws Exception {
        Instant base = Instant.parse("2024-11-09T10:00:00Z");
        insert(1L, "first", base.plusSeconds(100L));

        List<ChangeEvent> firstBatch = read(base.plusSeconds(95L));
        assertEquals(1, firstBatch.size(), "首次应拉到 1 条");
        assertEquals(base.plusSeconds(100L).toEpochMilli(), firstBatch.get(0).getSeq(),
                "seq 用毫秒时间戳表达，保证组内单调");

        // 时钟回拨：新行的时间戳比"已处理的最大时间戳"更早，但仍在重叠窗口内
        insert(2L, "rolled-back", base.plusSeconds(97L));

        List<ChangeEvent> secondBatch = read(base.plusSeconds(95L));
        assertEquals(1, secondBatch.size(),
                "回拨行必须被捞到；已处理过的 id=1 必须被去重过滤");
        assertEquals(List.of("2"), secondBatch.get(0).getPkTuple().stream()
                .map(String::valueOf).toList());
    }

    @Test
    @DisplayName("should_notDuplicate_When_sameRowFetchedTwice：重叠窗口内不重复产出")
    void should_notDuplicate_When_sameRowFetchedTwice() throws Exception {
        Instant base = Instant.parse("2024-11-09T11:00:00Z");
        insert(3L, "a", base);

        assertEquals(1, read(base.minusSeconds(5L)).size());
        assertEquals(0, read(base.minusSeconds(5L)).size(),
                "同一行第二次读取必须被去重（否则会重复应用）");
        assertEquals(1, poller.seenCount(mapping.getId()));
    }

    @Test
    @DisplayName("should_resetCursor_When_reset：重置后可重新校准")
    void should_resetCursor_When_reset() throws Exception {
        Instant base = Instant.parse("2024-11-09T12:00:00Z");
        insert(4L, "a", base);
        assertEquals(1, read(base.minusSeconds(5L)).size());

        poller.reset(mapping.getId());

        assertEquals(0, poller.seenCount(mapping.getId()));
        assertEquals(Instant.EPOCH, poller.cursor(mapping.getId()));
        assertEquals(1, read(base.minusSeconds(5L)).size(), "重置后应重新扫到该行");
    }

    @Test
    @DisplayName("should_returnNull_When_tableHasNoTimestampColumn：无时间列时明确降级")
    void should_returnNull_When_tableHasNoTimestampColumn() {
        assertNull(TimestampPoller.timestampColumn(tableMeta("t_plain")),
                "无 update_time 列时必须返回 null，由 pollOnce 抛可读错误");
        assertTrue(TimestampPoller.timestampColumn(table) != null);
    }
}
