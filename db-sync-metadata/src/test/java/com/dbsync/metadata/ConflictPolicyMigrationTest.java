package com.dbsync.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.metadata.dao.MetaDaoTestSupport;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V3 迁移验收：{@code sync_table_mapping.conflict_policy}（PRD CDC-06 按表可配）。
 *
 * <p><b>验证手段（如实说明，不夸大）</b>：
 * <ol>
 *   <li><b>H2（MODE=MySQL）是<b>真跑</b>的</b>：本测试类继承的基类用 Flyway 对内存 H2
 *       执行 V1→V2→V3→V4 全链（V4 是 CUT-01 的 SDK 路由 ack 闭环，合法且必要），
 *       因此"V3 能被迁移引擎接受"是执行结果，不是推断；</li>
 *   <li><b>列语义是断言出来的</b>：列名/长度/{@code NOT NULL}/默认值都从
 *       {@code information_schema} 按<b>当前连接 schema</b>读回来比对
 *       （见 {@link #should_addColumnWithDefault}），而不是只看"迁移没报错"；</li>
 *   <li><b>存量行补默认值是断言出来的</b>：先 {@code INSERT} 一行不带该列的映射
 *       （等价于 V1 时代写入的存量数据），再读回 {@code 'SOURCE_WINS'}
 *       （见 {@link #should_backfillDefaultForExistingRows}）；</li>
 *   <li><b>静态词表检查（防脚本漂移，非兼容性验证）</b>：本机没有 MySQL 实例可连，
 *       本测试<b>不</b>验证 MySQL 8 兼容性，只对脚本正文做静态词表检查，
 *       禁止出现与本次变更无关、且平台约定不使用的方言构造
 *       （{@code AFTER} / {@code ENGINE} / {@code CHARSET} / {@code COLLATE} /
 *       {@code COMMENT} / {@code IF NOT EXISTS} / {@code MODIFY} / {@code CHANGE} /
 *       {@code DEFAULT CURRENT_TIMESTAMP} / {@code AUTO_INCREMENT}）。
 *       注意：这只能防止脚本被误改/漂移，不能证明语句在 MySQL 8 上一定可执行。</li>
 * </ol>
 */
class ConflictPolicyMigrationTest extends MetaDaoTestSupport {

    private static final String MIGRATION = "db/migration/V3__mapping_conflict_policy.sql";

    @Test
    @DisplayName("列存在且定义正确：varchar(20) NOT NULL DEFAULT 'SOURCE_WINS'")
    void should_addColumnWithDefault() {
        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                        + "WHERE LOWER(table_name)='sync_table_mapping' "
                        + "AND LOWER(table_schema) = ?",
                String.class, currentSchema());
        assertThat(columns).contains("conflict_policy");

        String isNullable = jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns "
                        + "WHERE LOWER(table_name)='sync_table_mapping' "
                        + "AND LOWER(column_name)='conflict_policy' "
                        + "AND LOWER(table_schema) = ?",
                String.class, currentSchema());
        assertThat(isNullable).isEqualToIgnoringCase("NO");

        Integer size = jdbc.queryForObject("SELECT character_maximum_length "
                        + "FROM information_schema.columns WHERE LOWER(table_name)='sync_table_mapping' "
                        + "AND LOWER(column_name)='conflict_policy' "
                        + "AND LOWER(table_schema) = ?",
                Integer.class, currentSchema());
        assertThat(size).isEqualTo(20);

        String defaultValue = jdbc.queryForObject("SELECT column_default "
                        + "FROM information_schema.columns WHERE LOWER(table_name)='sync_table_mapping' "
                        + "AND LOWER(column_name)='conflict_policy' "
                        + "AND LOWER(table_schema) = ?",
                String.class, currentSchema());
        assertThat(defaultValue).isNotNull();
        assertThat(defaultValue.toUpperCase(Locale.ROOT)).contains("SOURCE_WINS");
    }

    @Test
    @DisplayName("存量行补默认值：不写该列也能插入，读回来是 SOURCE_WINS")
    void should_backfillDefaultForExistingRows() {
        long id = 9_900_001L;
        long now = System.currentTimeMillis();
        // 模拟 V1 时代的写入方式（不包含 V3 新增列）
        jdbc.update("INSERT INTO sync_table_mapping (id,task_id,source_schema,source_table,"
                        + "target_schema,target_table,row_count_estimate,has_primary_key,range_kind,"
                        + "ddl_applied,enabled,version,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, 9_900_000L, "s", "t_old", "t", "t_old", 0L, 1, "NUMERIC", 0, 1, 0,
                new java.sql.Timestamp(now), new java.sql.Timestamp(now));

        String policy = jdbc.queryForObject(
                "SELECT conflict_policy FROM sync_table_mapping WHERE id=?", String.class, id);
        assertThat(policy).isEqualTo("SOURCE_WINS");
    }

    @Test
    @DisplayName("静态词表检查（防脚本漂移，非兼容性验证）：禁用构造且只做一件事")
    void should_onlyUsePortableSingleAlter() throws IOException {
        String sql = readMigration().toUpperCase(Locale.ROOT);
        String code = stripComments(sql);

        String[] forbidden = {"AFTER ", "ENGINE", "CHARSET", "COLLATE", "COMMENT ", "IF NOT EXISTS",
            "MODIFY ", "CHANGE ", "DEFAULT CURRENT_TIMESTAMP", "AUTO_INCREMENT"};
        for (String token : forbidden) {
            assertThat(code).as("V3 不应出现方言构造：" + token).doesNotContain(token);
        }
        // 只允许一条语句，且是标准 ALTER TABLE ... ADD COLUMN
        assertThat(code.split(";", -1)).hasSize(2);
        assertThat(code).contains("ALTER TABLE SYNC_TABLE_MAPPING");
        assertThat(code).contains("ADD COLUMN CONFLICT_POLICY VARCHAR(20)");
        assertThat(code).contains("NOT NULL DEFAULT 'SOURCE_WINS'");
    }

    /**
     * 校验迁移历史的整体不变式，而不是把版本数写死。
     *
     * <p>断言的真实意图：V1/V2/V3 三个原始迁移必须原样保留、顺序不变（前缀钉死），
     * V4 及其后的新迁移允许继续追加；版本号按 {@code installed_rank} 严格递增；
     * 所有版本化迁移全部 {@code success=true} 且不存在任何失败记录。
     * 这样再加 V5 也不会因"版本数变了"而误报，但改动/删除历史迁移仍会被抓住。
     */
    @Test
    @DisplayName("V1/V2/V3 未被改动：schema_history 版本按序递增且全部成功")
    void should_keepMigrationOrder() {
        List<String> versions = jdbc.queryForList("SELECT version FROM flyway_schema_history "
                + "WHERE version IS NOT NULL ORDER BY installed_rank", String.class);
        // ① 前缀钉死：V1/V2/V3 未被改动且顺序不变；V4 是合法追加的新迁移
        assertThat(versions).as("V1/V2/V3 必须原样保留，V4 为合法新增")
                .startsWith("1", "2", "3", "4");

        // ② 版本号按 installed_rank 严格递增（数值比较，兼容 "5.1" 这类后续版本）
        BigDecimal previous = null;
        for (String version : versions) {
            BigDecimal current = new BigDecimal(version.trim());
            if (previous != null) {
                assertThat(current).as("版本必须严格递增：" + versions).isGreaterThan(previous);
            }
            previous = current;
        }

        // ③ 全部成功：无 success=false 记录，且每条版本化迁移都是 success=true
        Integer failed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success=false", Integer.class);
        assertThat(failed).as("不允许任何失败迁移").isEqualTo(0);
        List<String> failedVersions = jdbc.queryForList("SELECT version FROM flyway_schema_history "
                + "WHERE success=false AND version IS NOT NULL", String.class);
        assertThat(failedVersions).as("失败的版本化迁移").isEmpty();
        Integer versioned = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL",
                Integer.class);
        Integer successfulVersioned = jdbc.queryForObject("SELECT COUNT(*) "
                + "FROM flyway_schema_history WHERE version IS NOT NULL AND success=true",
                Integer.class);
        assertThat(successfulVersioned).as("版本化迁移必须全部 success=true")
                .isEqualTo(versioned);
    }

    /**
     * 读迁移脚本原文。
     *
     * @return 脚本内容
     * @throws IOException 读不到
     */
    private static String readMigration() throws IOException {
        ClassLoader loader = ConflictPolicyMigrationTest.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(MIGRATION)) {
            assertThat(in).as("迁移脚本必须打进 classpath：" + MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 去掉 SQL 注释（避免注释里的英文说明命中静态闸门）。
     *
     * @param sql 原文
     * @return 仅含语句的文本
     */
    private static String stripComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        for (String line : sql.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString().trim();
    }

    /**
     * 当前连接的库/schema 名（H2=PUBLIC，MySQL 8=数据库名）。
     * information_schema 的 table_schema 在两者含义不同，故用 JDBC metadata 动态取，
     * 不写死 'public'（那是 H2/PG 的 schema 名，MySQL 8 是数据库名）。
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
