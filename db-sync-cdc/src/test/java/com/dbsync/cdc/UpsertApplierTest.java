package com.dbsync.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.model.ChangeEvent;
import com.dbsync.common.model.TableMapping;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T30 验收：单条应用的幂等性与删除。
 */
class UpsertApplierTest extends CdcTestSupport {

    private UpsertApplier applier;
    private long taskId;
    private TableMapping mapping;

    /**
     * 建目标表。
     */
    @BeforeEach
    void setUpApplier() {
        createTargetTable("t_ap");
        applier = new UpsertApplier();
        taskId = newTask(16, 4, IncrementalMode.TRIGGER_LOG).getId();
        mapping = newMapping(taskId, "t_ap");
    }

    @Test
    @DisplayName("should_idempotent_When_applySameEvent10Times：同事件 10 次只有 1 行且值不变")
    void should_idempotent_When_applySameEvent10Times() throws Exception {
        ChangeEvent event = change(taskId, mapping, 1L, "v1", 1L, 0);

        try (Connection connection = targetDs.getConnection()) {
            connection.setAutoCommit(false);
            for (int i = 0; i < 10; i++) {
                ApplyResult result = applier.apply(connection, dialect, tableMeta("t_ap"), event);
                assertTrue(result.isApplied());
                connection.commit();
            }
        }

        assertEquals(1L, countRows("t_ap"), "upsert 必须幂等");
        assertEquals("v1", nameOf("t_ap", 1L));
    }

    @Test
    @DisplayName("should_overwrite_When_sameKeyDifferentValue：同主键不同值应覆盖")
    void should_overwrite_When_sameKeyDifferentValue() throws Exception {
        try (Connection connection = targetDs.getConnection()) {
            connection.setAutoCommit(false);
            applier.apply(connection, dialect, tableMeta("t_ap"), change(taskId, mapping, 2L, "a", 1L, 0));
            applier.apply(connection, dialect, tableMeta("t_ap"), change(taskId, mapping, 2L, "b", 2L, 0));
            connection.commit();
        }

        assertEquals(1L, countRows("t_ap"));
        assertEquals("b", nameOf("t_ap", 2L));
    }

    @Test
    @DisplayName("should_deleteRow_When_opIsDelete：DELETE 走真实删除")
    void should_deleteRow_When_opIsDelete() throws Exception {
        try (Connection connection = targetDs.getConnection()) {
            connection.setAutoCommit(false);
            applier.apply(connection, dialect, tableMeta("t_ap"), change(taskId, mapping, 3L, "x", 1L, 0));
            connection.commit();
            assertEquals("x", nameOf("t_ap", 3L));
        }
        try (Connection connection = targetDs.getConnection()) {
            connection.setAutoCommit(false);
            applier.apply(connection, dialect, tableMeta("t_ap"),
                    deleteChange(taskId, mapping, 3L, 2L, 0));
            connection.commit();
        }

        assertNull(nameOf("t_ap", 3L), "删除后应查不到该行");
        assertEquals(0L, countRows("t_ap"));
    }

    @Test
    @DisplayName("should_reportZeroRows_When_deleteMissingRow：删不存在的行不算失败")
    void should_reportZeroRows_When_deleteMissingRow() throws Exception {
        try (Connection connection = targetDs.getConnection()) {
            connection.setAutoCommit(false);
            ApplyResult result = applier.apply(connection, dialect, tableMeta("t_ap"),
                    deleteChange(taskId, mapping, 999L, 1L, 0));
            connection.commit();
            assertTrue(result.isApplied());
            assertEquals(0, result.getRowsAffected());
        }
    }

    @Test
    @DisplayName("should_upsertSqlFromDialect_When_applyInsert：SQL 必须来自方言且是幂等 upsert")
    void should_upsertSqlFromDialect_When_applyInsert() {
        String sql = dialect.buildUpsertSql(tableMeta("t_ap"), List.of("id"), List.of("id", "name"));
        assertTrue(sql.toUpperCase().startsWith("INSERT INTO"), sql);
        assertTrue(sql.contains("`id`"), "标识符必须被方言引号化：" + sql);
        assertTrue(sql.contains("`name`"), "所有列都必须被方言引号化：" + sql);
        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE"),
                "MySQL 方言必须走 upsert 幂等路径，而不是普通 INSERT：" + sql);
        assertTrue(sql.contains("`name`=VALUES(`name`)"),
                "非主键列必须在主键冲突时覆盖（幂等性来源）：" + sql);
        assertTrue(sql.contains("VALUES (?, ?)"), "参数必须以占位符绑定而非拼值：" + sql);
    }
}
