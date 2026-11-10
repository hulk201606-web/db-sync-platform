package com.dbsync.core.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dbsync.common.enums.DbType;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.PrimaryKeyMeta;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.WriteOptions;
import com.dbsync.core.CoreTestSupport;
import com.dbsync.dialect.Dialect;
import com.dbsync.dialect.DialectRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T20 验收：幂等 upsert、整批失败回滚、NULL 绑定、方言 SQL 走 Dialect、批量性能。
 */
class JdbcBatchWriterTest extends CoreTestSupport {

    private final JdbcBatchWriter writer = new JdbcBatchWriter();
    private Dialect dialect;

    /**
     * 建目标表。
     */
    @BeforeEach
    void setUpTable() {
        jdbc.execute("CREATE TABLE t_target (id BIGINT NOT NULL PRIMARY KEY, name VARCHAR(64))");
        dialect = DialectRegistry.get(DbType.MYSQL);
    }

    /**
     * 目标表元数据。
     *
     * @return 表元数据
     */
    private TableMeta target() {
        ColumnMeta id = ColumnMeta.builder("id", 0, "BIGINT").jdbcType(Types.BIGINT).size(19)
                .nullable(false).build();
        ColumnMeta name = ColumnMeta.builder("name", 1, "VARCHAR").jdbcType(Types.VARCHAR).size(64)
                .nullable(true).build();
        return TableMeta.of(null, "t_target", List.of(id, name),
                new PrimaryKeyMeta("pk_id", List.of("id")));
    }

    @Test
    @DisplayName("should_upsertIdempotently_When_sameRowTwice：同一行 upsert 两次仍只有 1 行")
    void should_upsertIdempotently_When_sameRowTwice() {
        List<Object[]> rows = List.<Object[]>of(new Object[] {1L, "alice"});
        try (Connection connection = dataSource.getConnection()) {
            WriteResult first = writer.upsert(connection, dialect, target(), rows,
                    WriteOptions.upsert());
            connection.commit();
            assertThat(first.rowsAffected()).isEqualTo(1);
            assertThat(first.committed()).isFalse();

            List<Object[]> updated = List.<Object[]>of(new Object[] {1L, "alice-2"});
            writer.upsert(connection, dialect, target(), updated, WriteOptions.upsert());
            connection.commit();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM t_target", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT name FROM t_target WHERE id=1", String.class))
                .isEqualTo("alice-2");
    }

    @Test
    @DisplayName("should_rollbackWholeBatch_When_oneRowFails：insert 冲突时整批不可部分成功")
    void should_rollbackWholeBatch_When_oneRowFails() {
        jdbc.update("INSERT INTO t_target (id,name) VALUES (2,'exists')");
        List<Object[]> rows = new ArrayList<Object[]>();
        rows.add(new Object[] {3L, "new"});
        rows.add(new Object[] {2L, "dup"});
        Throwable thrown = null;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                writer.insert(connection, dialect, target(), rows, WriteOptions.insertOnly());
            } finally {
                connection.rollback();
            }
        } catch (Throwable e) {
            thrown = e;
        }
        assertThat(thrown).isNotNull();
        // 整批回滚：id=3 也不能落库
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM t_target WHERE id=3", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM t_target", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("should_bindNullLiteral_When_valueIsNull：NULL 走 setNull 而不是拼字符串")
    void should_bindNullLiteral_When_valueIsNull() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeBatch()).thenReturn(new int[] {1});

        writer.upsert(connection, dialect, target(),
                List.<Object[]>of(new Object[] {1L, null}), WriteOptions.upsert());

        verify(statement).setNull(org.mockito.ArgumentMatchers.eq(2), anyInt());
        verify(statement).setObject(org.mockito.ArgumentMatchers.eq(1), any());
    }

    @Test
    @DisplayName("should_pathThroughDialectSql_When_upsert：Upsert SQL 必须由 Dialect 生成")
    void should_pathThroughDialectSql_When_upsert() throws Exception {
        Dialect mocked = mock(Dialect.class);
        when(mocked.buildUpsertSql(any(), anyList(), anyList()))
                .thenReturn("INSERT INTO `t_target` (`id`,`name`) VALUES (?,?) "
                        + "ON DUPLICATE KEY UPDATE `name`=VALUES(`name`)");
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeBatch()).thenReturn(new int[] {1});

        WriteResult result = writer.upsert(connection, mocked, target(),
                List.<Object[]>of(new Object[] {9L, "x"}), WriteOptions.upsert());

        verify(mocked).buildUpsertSql(any(), anyList(), anyList());
        verify(connection).prepareStatement(
                org.mockito.ArgumentMatchers.contains("ON DUPLICATE KEY UPDATE"));
        assertThat(result.committed()).isFalse();
        assertThat(result.rowsAffected()).isEqualTo(1);
    }

    @Test
    @DisplayName("should_useRealMySqlDialectSql_When_upsert：真实 MySqlDialect 生成的 upsert SQL 被采用")
    void should_useRealMySqlDialectSql_When_upsert() throws Exception {
        Dialect realMySql = DialectRegistry.get(DbType.MYSQL);
        String expected = realMySql.buildUpsertSql(target(), List.of("id"),
                List.of("id", "name"));
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeBatch()).thenReturn(new int[] {1});

        WriteResult result = writer.upsert(connection, realMySql, target(),
                List.<Object[]>of(new Object[] {9L, "x"}), WriteOptions.upsert());

        // 关键：prepareStatement 收到的 SQL 正是真实方言的产物，而非测试内硬编码的替身
        verify(connection).prepareStatement(expected);
        assertThat(expected)
                .contains("INSERT INTO")
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("`name`=VALUES(`name`)");
        assertThat(result.rowsAffected()).isEqualTo(1);
    }

    @Test
    @DisplayName("should_write20kRowsQuickly：2 万行 batch=1000 在 10s 内完成")
    void should_write20kRowsQuickly() {
        List<Object[]> rows = new ArrayList<Object[]>(20_000);
        for (long i = 1; i <= 20_000; i++) {
            rows.add(new Object[] {Long.valueOf(i), "n" + i});
        }
        long start = System.currentTimeMillis();
        try (Connection connection = dataSource.getConnection()) {
            for (int from = 0; from < rows.size(); from += 1000) {
                List<Object[]> chunk = rows.subList(from, Math.min(from + 1000, rows.size()));
                writer.upsert(connection, dialect, target(), chunk, WriteOptions.upsert());
            }
            connection.commit();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).isLessThan(10_000L);
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM t_target", Integer.class))
                .isEqualTo(20_000);
    }

    @Test
    @DisplayName("should_rejectWrongColumnCount：行列数不匹配立即报错，不写脏数据")
    void should_rejectWrongColumnCount() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString()))
                .thenReturn(mock(PreparedStatement.class));
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                writer.upsert(connection, dialect, target(),
                        List.<Object[]>of(new Object[] {1L}), WriteOptions.upsert())))
                .isNotNull();
    }

    @Test
    @DisplayName("should_returnZero_When_rowsEmpty：空批次不建语句")
    void should_returnZero_When_rowsEmpty() throws Exception {
        Connection connection = mock(Connection.class);
        WriteResult result = writer.upsert(connection, dialect, target(), List.of(),
                WriteOptions.upsert());
        assertThat(result.rowsAffected()).isZero();
        assertThat(result.committed()).isFalse();
    }
}
