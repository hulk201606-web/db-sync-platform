package com.dbsync.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.PrecheckReport;
import com.dbsync.common.model.PrimaryKeyMeta;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.model.TableMeta;
import com.dbsync.dialect.Dialect;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T26 验收：8 类阻断项各 1 个用例 + {@code PrecheckReport.hasBlocking()} 语义。
 *
 * <p>题目要求"用 H2 造场景"。能用真库的（缺表、无主键、标识符超长、主键为 LOB、
 * 循环外键）都走真 H2；"无 CREATE 权限"与"空间不足"这两类无法在 H2 上稳定造出来
 * （H2 内存库没有表空间配额），因此用 mock 的 {@link Connection} 让驱动返回真实的
 * SQLState/错误码，验证的是<b>归类逻辑</b>而不是造一个不可复现的环境。
 */
class PrecheckTest extends MigrationTestSupport {

    private static final long TASK_ID = 5_000L;

    private MigrationPrecheck precheck;
    private List<String> blocking;

    /**
     * 被测对象（不走 {@code run()}：那需要真实数据源注册表，与本节的断言无关）。
     */
    @BeforeEach
    void setUpPrecheck() {
        precheck = new MigrationPrecheck(taskDao, mappingDao, null);
        blocking = new ArrayList<>();
    }

    /**
     * 造一个表映射。
     *
     * @param target 目标表名
     * @return 映射
     */
    private TableMapping mapping(String target) {
        return new TableMapping(1L, TASK_ID, "PUBLIC", "t_src", "PUBLIC", target, true, "NUMERIC",
                100L, null, true, true);
    }

    @Test
    @DisplayName("阻断 1：目标表缺失")
    void should_block_When_targetTableMissing() {
        precheck.checkTargetTableExists(mapping("t_missing"), null, blocking);

        assertEquals(1, blocking.size());
        assertTrue(blocking.get(0).contains("目标表缺失"), blocking.get(0));
    }

    @Test
    @DisplayName("阻断 4：目标无主键或唯一约束")
    void should_block_When_targetHasNoUniqueKey() {
        TableMeta noPk = TableMeta.of("PUBLIC", "t_nopk", List.of(
                ColumnMeta.builder("id", 0, "BIGINT").jdbcType(java.sql.Types.BIGINT).build()),
                PrimaryKeyMeta.NONE);

        precheck.checkUniqueKey(mapping("t_nopk"), noPk, blocking);

        assertEquals(1, blocking.size());
        assertTrue(blocking.get(0).contains("无主键/唯一约束"), blocking.get(0));
    }

    @Test
    @DisplayName("阻断 5：标识符超长（MySQL 上限 64）")
    void should_block_When_identifierTooLong() {
        String longName = "t_" + "x".repeat(80);
        precheck.checkIdentifierLength(mapping(longName), dialect, blocking);

        assertEquals(1, blocking.size());
        assertTrue(blocking.get(0).contains("标识符超长"), blocking.get(0));
        assertTrue(blocking.get(0).contains("64"), "应给出该库的上限：" + blocking.get(0));
    }

    @Test
    @DisplayName("阻断 6：类型不支持（方言拒绝映射）")
    void should_block_When_typeUnsupported() {
        Dialect mocked = mock(Dialect.class);
        when(mocked.type()).thenReturn(com.dbsync.common.enums.DbType.ORACLE);
        when(mocked.capabilities()).thenReturn(null);
        when(mocked.mapType(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DbSyncException(ErrorCode.DIALECT_UNSUPPORTED, "INTERVAL DAY"));

        precheck.checkTypeSupported(mapping("t_weird"), tableMeta("t_weird", 10L), mocked, blocking);

        assertTrue(blocking.stream().anyMatch(s -> s.contains("类型不支持")), blocking.toString());
    }

    @Test
    @DisplayName("阻断 7：主键列为 LOB")
    void should_block_When_primaryKeyIsLob() {
        ColumnMeta lobPk = ColumnMeta.builder("id", 0, "LONGTEXT")
                .jdbcType(java.sql.Types.LONGVARCHAR).lob(true).build();
        TableMeta meta = new TableMeta("PUBLIC", "t_lobpk", List.of(lobPk),
                new PrimaryKeyMeta("pk_lob", List.of("id")), List.of(), 10L, null);

        precheck.checkPkNotLob(mapping("t_lobpk"), meta, blocking);

        assertEquals(1, blocking.size());
        assertTrue(blocking.get(0).contains("主键为 LOB"), blocking.get(0));
    }

    @Test
    @DisplayName("阻断 8：目标库循环外键（真 H2 造环）")
    void should_block_When_foreignKeyCycleExists() throws Exception {
        exec(targetDs,
                "CREATE TABLE t_a (id BIGINT PRIMARY KEY, b_id BIGINT)",
                "CREATE TABLE t_b (id BIGINT PRIMARY KEY, a_id BIGINT)",
                "ALTER TABLE t_a ADD CONSTRAINT fk_a_b FOREIGN KEY (b_id) REFERENCES t_b(id)",
                "ALTER TABLE t_b ADD CONSTRAINT fk_b_a FOREIGN KEY (a_id) REFERENCES t_a(id)");
        List<TableMapping> mappings = List.of(mapping("t_a"), mapping("t_b"));

        try (Connection connection = targetDs.getConnection()) {
            precheck.checkForeignKeyCycle(connection, mappings, blocking);
        }

        assertEquals(1, blocking.size(), blocking.toString());
        assertTrue(blocking.get(0).contains("循环外键"), blocking.get(0));
        assertTrue(blocking.get(0).contains("T_A") || blocking.get(0).contains("t_a"),
                blocking.get(0));
    }

    @Test
    @DisplayName("非阻断：无环外键不应报错（真 H2）")
    void should_notBlock_When_foreignKeyIsAcyclic() throws Exception {
        exec(targetDs,
                "CREATE TABLE t_parent (id BIGINT PRIMARY KEY)",
                "CREATE TABLE t_child (id BIGINT PRIMARY KEY, p_id BIGINT, "
                        + "CONSTRAINT fk_child FOREIGN KEY (p_id) REFERENCES t_parent(id))");

        try (Connection connection = targetDs.getConnection()) {
            precheck.checkForeignKeyCycle(connection, List.of(mapping("t_child")), blocking);
        }

        assertTrue(blocking.isEmpty(), blocking.toString());
    }

    @Test
    @DisplayName("阻断 2：无 CREATE 权限（SQLState 42000）")
    void should_block_When_createPrivilegeDenied() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString()))
                .thenThrow(new SQLException("CREATE command denied to user 'app'@'10.0.0.1'",
                        "42000", 1044));

        Optional<String> failure = precheck.probe(connection, dialect, "dbsync_probe_x");

        assertTrue(failure.isPresent(), "权限不足必须阻断");
        assertTrue(failure.get().contains("无 CREATE 权限"), failure.get());
    }

    @Test
    @DisplayName("阻断 3：目标空间不足（磁盘满）")
    void should_block_When_targetIsOutOfSpace() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString()))
                .thenThrow(new SQLException("could not extend file: No space left on device",
                        "53100", 0));

        Optional<String> failure = precheck.probe(connection, dialect, "dbsync_probe_y");

        assertTrue(failure.isPresent(), "空间不足必须阻断");
        assertTrue(failure.get().contains("目标空间不足"), failure.get());
    }

    @Test
    @DisplayName("非阻断：探针建表成功后立即删除（真 H2）")
    void should_pass_When_probeSucceeds() throws Exception {
        try (Connection connection = targetDs.getConnection()) {
            Optional<String> failure = precheck.probe(connection, dialect, "dbsync_probe_ok");
            assertFalse(failure.isPresent(), failure.orElse(""));
        }
        // 探针表不应残留
        try (Connection connection = targetDs.getConnection();
             Statement statement = connection.createStatement();
             java.sql.ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.tables "
                             + "WHERE table_name='dbsync_probe_ok'")) {
            assertTrue(rs.next());
            assertEquals(0L, rs.getLong(1), "探针表必须被删掉");
        }
    }

    @Test
    @DisplayName("PrecheckReport.hasBlocking：阻断项非空即 true，警告不影响通过")
    void should_reportHasBlockingSemantics() {
        PrecheckReport clean = new PrecheckReport(TASK_ID, List.of(), List.of("仅提醒"));
        PrecheckReport dirty = new PrecheckReport(TASK_ID, List.of("[目标表缺失] t"),
                List.of());

        assertFalse(clean.hasBlocking());
        assertTrue(clean.isPass());
        assertTrue(dirty.hasBlocking());
        assertFalse(dirty.isPass());
    }

    @Test
    @DisplayName("findCycle：DFS 能定位三元环")
    void should_findCycle_WhenGraphHasThreeNodeLoop() {
        Map<String, Set<String>> graph = Map.of(
                "A", Set.of("B"),
                "B", Set.of("C"),
                "C", Set.of("A"));

        List<String> cycle = MigrationPrecheck.findCycle(graph);

        assertNotNull(cycle);
        assertFalse(cycle.isEmpty());
        assertEquals(cycle.get(0), cycle.get(cycle.size() - 1), "环路径首尾必须闭合：" + cycle);
    }

    @Test
    @DisplayName("classifyProbeFailure：未知错误归到 OTHER")
    void should_classifyUnknownProbeFailure() {
        assertEquals(MigrationPrecheck.ProbeFailure.OTHER,
                MigrationPrecheck.classifyProbeFailure(new SQLException("syntax error", "42000x", 1)));
        assertEquals(MigrationPrecheck.ProbeFailure.PRIVILEGE,
                MigrationPrecheck.classifyProbeFailure(new SQLException("denied", "42501", 0)));
        assertEquals(MigrationPrecheck.ProbeFailure.SPACE,
                MigrationPrecheck.classifyProbeFailure(
                        new SQLException("ORA-01653: unable to extend table", "72000", 1653)));
    }

    @Test
    @DisplayName("DatabaseMetaData 为空时不因 FK 检查而抛异常")
    void should_notThrow_When_metadataUnavailable() throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metaData);
        when(connection.getCatalog()).thenReturn("c");
        when(metaData.getImportedKeys(anyString(), org.mockito.ArgumentMatchers.any(),
                anyString())).thenThrow(new SQLException("not supported"));

        precheck.checkForeignKeyCycle(connection, List.of(mapping("t_x")), blocking);

        assertTrue(blocking.isEmpty(), "读不到 FK 元数据时只能跳过，不能误报阻断");
    }
}
