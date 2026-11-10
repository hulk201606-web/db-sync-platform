package com.dbsync.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.model.TableMapping;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T26 验收：行数核数（少插 5 行 → 差异 5；全等 → 空列表）。
 *
 * <p>通过 {@code RowCountVerifier(syncTaskDao, tableMappingDao)} 构造，
 * 直接对 H2 的源/目标连接做真实 {@code COUNT(*)}，不经过数据源注册表
 * （{@code DataSourceHandle} 是 final 类且需要加密口令，与本断言无关）。
 */
class RowCountVerifierTest extends MigrationTestSupport {

    private RowCountVerifier verifier;

    /**
     * 被测对象。
     */
    @BeforeEach
    void setUpVerifier() {
        verifier = new RowCountVerifier(taskDao, mappingDao);
    }

    /**
     * 造映射（源/目标同名表）。
     *
     * @param table 表名
     * @return 映射
     */
    private TableMapping mapping(String table) {
        return new TableMapping(1L, 1L, "PUBLIC", table, "PUBLIC", table, true, "NUMERIC", 100L,
                null, true, true);
    }

    @Test
    @DisplayName("should_reportDiffOf5_When_targetShortOf5Rows：少插 5 行")
    void should_reportDiffOf5_When_targetShortOf5Rows() throws Exception {
        createBothTables("t_rc1");
        insertRows(sourceDs, "t_rc1", 1L, 100L);
        insertRows(targetDs, "t_rc1", 1L, 95L);

        try (Connection source = sourceDs.getConnection();
             Connection target = targetDs.getConnection()) {
            List<RowCountDiff> diffs = verifier.compareAll(List.of(mapping("t_rc1")), source,
                    dialect, target, dialect);

            assertEquals(1, diffs.size());
            RowCountDiff diff = diffs.get(0);
            assertEquals(100L, diff.getSourceRows());
            assertEquals(95L, diff.getTargetRows());
            assertEquals(-5L, diff.diff(), "差异 = 目标 - 源 = -5");
            assertFalse(diff.isMatch());
            assertTrue(diff.toString().contains("t_rc1"));
        }
    }

    @Test
    @DisplayName("should_returnEmptyList_When_allCountsMatch：全等 → 空列表")
    void should_returnEmptyList_When_allCountsMatch() throws Exception {
        createBothTables("t_rc2");
        insertRows(sourceDs, "t_rc2", 1L, 50L);
        insertRows(targetDs, "t_rc2", 1L, 50L);

        try (Connection source = sourceDs.getConnection();
             Connection target = targetDs.getConnection()) {
            List<RowCountDiff> diffs = verifier.compareAll(List.of(mapping("t_rc2")), source,
                    dialect, target, dialect);

            assertTrue(diffs.isEmpty(), "一致的表不应出现在差异列表里：" + diffs);
        }
    }

    @Test
    @DisplayName("should_reportOnlyMismatchedTables_When_multiTable：只报不一致的表")
    void should_reportOnlyMismatchedTables_When_multiTable() throws Exception {
        createBothTables("t_rc3");
        createBothTables("t_rc4");
        insertRows(sourceDs, "t_rc3", 1L, 10L);
        insertRows(targetDs, "t_rc3", 1L, 10L);
        insertRows(sourceDs, "t_rc4", 1L, 10L);
        insertRows(targetDs, "t_rc4", 1L, 12L);

        try (Connection source = sourceDs.getConnection();
             Connection target = targetDs.getConnection()) {
            List<RowCountDiff> diffs = verifier.compareAll(
                    List.of(mapping("t_rc3"), mapping("t_rc4")), source, dialect, target,
                    dialect);

            assertEquals(1, diffs.size());
            assertEquals(2L, diffs.get(0).diff(), "目标多插 2 行应为 +2");
        }
    }

    @Test
    @DisplayName("should_wrapSqlError_When_tableMissing：表不存在时说清楚是哪张表")
    void should_wrapSqlError_When_tableMissing() throws Exception {
        try (Connection source = sourceDs.getConnection();
             Connection target = targetDs.getConnection()) {
            DbSyncException e = assertThrows(DbSyncException.class,
                    () -> verifier.compareAll(List.of(mapping("t_not_exist")), source, dialect,
                            target, dialect));
            assertTrue(e.getMessage().contains("t_not_exist"), e.getMessage());
        }
    }

    @Test
    @DisplayName("should_quoteIdentifiers_When_schemaGiven：标识符必须走方言 quote")
    void should_quoteIdentifiers_When_schemaGiven() {
        assertEquals("`PUBLIC`.`t_rc`", RowCountVerifier.qualified(dialect, "PUBLIC", "t_rc"));
        assertEquals("`t_rc`", RowCountVerifier.qualified(dialect, null, "t_rc"));
        assertEquals("PUBLIC.t_rc", RowCountVerifier.readable("PUBLIC", "t_rc"));
    }
}
