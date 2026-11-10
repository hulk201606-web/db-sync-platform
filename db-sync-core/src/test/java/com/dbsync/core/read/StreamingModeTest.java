package com.dbsync.core.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dbsync.common.enums.DbType;
import com.dbsync.dialect.Capabilities;
import com.dbsync.dialect.Dialect;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T19 验收（★ 架构 R2 的防线）：流式读取必须按方言开对"游标"，否则静默退化为全量加载。
 */
class StreamingModeTest {

    /**
     * 造能力集。
     *
     * @param needsCursor 是否需要游标模式（非自动提交）
     * @return 能力集
     */
    private Capabilities caps(boolean needsCursor) {
        return new Capabilities(false, false, false, false, false, false, needsCursor, true, true,
                false, true, false, 64, 1000, null);
    }

    /**
     * 造方言替身。
     *
     * @param type        数据库类型
     * @param needsCursor 是否需要游标模式
     * @return 方言 mock
     */
    private Dialect dialect(DbType type, boolean needsCursor) {
        Dialect dialect = mock(Dialect.class);
        when(dialect.type()).thenReturn(type);
        when(dialect.capabilities()).thenReturn(caps(needsCursor));
        when(dialect.quote(anyString())).thenAnswer(inv -> "`" + inv.getArgument(0) + "`");
        when(dialect.buildLowerPredicate(anyString()))
                .thenAnswer(inv -> inv.getArgument(0) + " > ?");
        when(dialect.buildRangePredicate(anyString(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0) + " <= ?");
        return dialect;
    }

    @Test
    @DisplayName("should_disableAutoCommit_When_oracleDialect：Oracle 必须关自动提交（否则 fetchSize 无效）")
    void should_disableAutoCommit_When_oracleDialect() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        Dialect oracle = dialect(DbType.ORACLE, true);
        new JdbcReader().read(connection, oracle, ReadFixture.table(), ReadFixture.fullRange(),
                ReadFixture.options(), (row, index) -> { });

        verify(connection).setAutoCommit(false);
        verify(statement).setFetchSize(org.mockito.ArgumentMatchers.intThat(v -> v >= 500));
    }

    @Test
    @DisplayName("should_useIntegerMinValue_When_mysqlDialect：MySQL 必须 TYPE_FORWARD_ONLY + Integer.MIN_VALUE")
    void should_useIntegerMinValue_When_mysqlDialect() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        Dialect mysql = dialect(DbType.MYSQL, false);
        new JdbcReader().read(connection, mysql, ReadFixture.table(), ReadFixture.fullRange(),
                ReadFixture.options(), (row, index) -> { });

        verify(connection).prepareStatement(anyString(), org.mockito.ArgumentMatchers.eq(
                ResultSet.TYPE_FORWARD_ONLY), org.mockito.ArgumentMatchers.eq(
                ResultSet.CONCUR_READ_ONLY));
        verify(statement).setFetchSize(Integer.MIN_VALUE);
        // MySQL 不需要改 autoCommit
        verify(connection, never()).setAutoCommit(false);
    }

    @Test
    @DisplayName("should_disableAutoCommit_When_postgresDialect：PG 同样需要非自动提交")
    void should_disableAutoCommit_When_postgresDialect() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        new JdbcReader().read(connection, dialect(DbType.POSTGRESQL, false),
                ReadFixture.table(), ReadFixture.fullRange(), ReadFixture.options(),
                (row, index) -> { });

        verify(connection).setAutoCommit(false);
        verify(statement).setFetchSize(org.mockito.ArgumentMatchers.intThat(v -> v > 0));
    }

    @Test
    @DisplayName("should_closeResultSet_When_callbackThrows：回调抛异常时 ResultSet/Statement 仍被关闭")
    void should_closeResultSet_When_callbackThrows() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.getAutoCommit()).thenReturn(false);
        when(connection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true);
        when(resultSet.getObject(anyInt())).thenReturn("v");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                new JdbcReader().read(connection, dialect(DbType.MYSQL, false),
                        ReadFixture.table(), ReadFixture.fullRange(), ReadFixture.options(),
                        (row, index) -> {
                            throw new java.sql.SQLException("回调故意失败");
                        }))).isNotNull();

        verify(resultSet).close();
        verify(statement).close();
        // 连接不归 reader 关闭（归池）
        verify(connection, never()).close();
    }

    @Test
    @DisplayName("should_keepConnectionOpen_When_readSucceeded：读成功后不关连接（连接归池）")
    void should_keepConnectionOpen_When_readSucceeded() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.getAutoCommit()).thenReturn(false);
        when(connection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        long rows = new JdbcReader().read(connection, dialect(DbType.DB2, false),
                ReadFixture.table(), ReadFixture.fullRange(), ReadFixture.options(),
                (row, index) -> { });
        assertThat(rows).isZero();
        verify(connection, never()).close();
    }
}
