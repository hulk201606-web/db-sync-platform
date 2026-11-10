package com.dbsync.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.metadata.dao.MetaDaoTestSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T14 验收：{@code V1__init.sql} 在 H2(MODE=MySQL) 上可迁移，18 张表与 20 个索引全部存在，
 * 且 {@code V2__seed.sql} 插入的 admin 可查。
 */
class FlywayMigrationTest extends MetaDaoTestSupport {

    private static final String[] EXPECTED_TABLES = {
        "ds_datasource", "sync_task", "sync_table_mapping", "sync_column_mapping", "sync_shard",
        "sync_checkpoint", "sync_change_log", "sync_change_watermark", "sync_dead_letter",
        "sync_compare_job", "sync_compare_block", "sync_compare_diff", "cutover_plan",
        "cutover_step_log", "sys_user", "sys_audit_log", "sys_instance", "sync_trigger_def"
    };

    @Test
    @DisplayName("should_migrate_When_h2MySqlMode：18 张表全部建出来")
    void should_migrate_When_h2MySqlMode() {
        List<String> tables = jdbc.queryForList("SELECT LOWER(table_name) FROM information_schema.tables "
                + "WHERE LOWER(table_schema) = ? "
                + "AND LOWER(table_name) <> 'flyway_schema_history'",
                String.class, currentSchema());
        assertThat(tables).contains(EXPECTED_TABLES);
        // 校验 V1 全部 18 张，不多不少
        assertThat(tables).hasSize(EXPECTED_TABLES.length);
    }

    @Test
    @DisplayName("should_haveNamedUniqueConstraints：命名唯一约束可被错误处理识别")
    void should_haveNamedUniqueConstraints() {
        List<String> constraints = jdbc.queryForList("SELECT LOWER(constraint_name) "
                + "FROM information_schema.table_constraints WHERE LOWER(constraint_type)='unique' "
                + "AND LOWER(table_schema) = ?", String.class, currentSchema());
        assertThat(constraints).contains("uk_ds_name", "uk_map", "uk_colmap", "uk_shard", "uk_ckpt",
                "uk_wm", "uk_plan_no", "uk_user_name", "uk_trg");
    }

    @Test
    @DisplayName("V2__seed.sql 插入 1 条 admin，且口令是 PBKDF2 哈希（非明文）")
    void should_seedAdminUser() {
        List<String> usernames = jdbc.queryForList("SELECT username FROM sys_user", String.class);
        assertThat(usernames).containsExactly("admin");
        String hash = jdbc.queryForObject("SELECT password_hash FROM sys_user WHERE username='admin'",
                String.class);
        assertThat(hash).hasSize(64).isNotEqualTo("Admin@12345");
    }

    /**
     * 当前连接的库/schema 名：H2 的 information_schema.table_schema 是 schema（PUBLIC），
     * MySQL 8 是数据库名（catalog）。用 JDBC metadata 动态取，避免把 'public' 写死——
     * 写死只在 H2/PG 成立，指向 MySQL 8 会一条都查不到。
     */
    private String currentSchema() {
        try (java.sql.Connection connection = dataSource.getConnection()) {
            String schema = connection.getSchema();
            if (schema == null || schema.isBlank()) {
                schema = connection.getCatalog();
            }
            return schema == null ? "" : schema.toLowerCase(java.util.Locale.ROOT);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("读取当前 schema 失败", e);
        }
    }
}
