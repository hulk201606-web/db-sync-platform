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
 * V4 迁移验收：{@code cutover_plan.acked_version}（PRD CUT-01 路由 ack 闭环）。
 *
 * <p><b>验证手段（如实说明，不夸大）</b>：
 * <ol>
 *   <li><b>H2（MODE=MySQL）是<b>真跑</b>的</b>：本测试类继承的基类用 Flyway 对内存 H2
 *       执行 V1→V2→V3→V4 全链；{@link #should_runFullChainV1ToV4OnH2()} 直接查
 *       {@code flyway_schema_history} 里 V4 的 {@code success=true} 记录与脚本名，
 *       并确认列真的出现在库里——"V4 被引擎接受"是执行结果，不是推测；</li>
 *   <li><b>列语义是断言出来的</b>：列名/{@code NOT NULL}/类型/默认值都从
 *       {@code information_schema} 按<b>当前连接 schema</b>读回来比对（见
 *       {@link #should_addAckedVersionColumnWithDefault()}），而不是只看"迁移没报错"；</li>
 *   <li><b>存量行补默认值是断言出来的</b>：按 V1 时代的列集 {@code INSERT} 一行不带该列
 *       的计划（等价于 V1 时代写入的存量数据），再读回 {@code 0}
 *       （见 {@link #should_backfillDefaultForExistingRows()}）；</li>
 *   <li><b>静态词表检查（防脚本漂移，非兼容性验证）</b>：本机没有 MySQL 实例可连，
 *       本测试<b>不</b>验证 MySQL 8 兼容性，只对脚本正文做静态词表检查，
 *       禁止出现与本次变更无关、且平台约定不使用的方言构造
 *       （{@code AFTER} / {@code ENGINE} / {@code CHARSET} / {@code COLLATE} /
 *       {@code COMMENT} / {@code IF NOT EXISTS} / {@code MODIFY} / {@code CHANGE} /
 *       {@code DEFAULT CURRENT_TIMESTAMP} / {@code AUTO_INCREMENT}），并要求只有一条
 *       标准 {@code ADD COLUMN ... NOT NULL DEFAULT <字面量>} 语句——与 V3 词表检查同构。
 *       注意：这只能防止脚本被误改/漂移，不能证明语句在 MySQL 8 上一定可执行；</li>
 *   <li><b>迁移历史是断言出来的</b>：{@code flyway_schema_history} 版本序列含 1/2/3/4
 *       且不存在任何 {@code success=false} 记录
 *       （见 {@link #should_keepFullHistoryWithoutFailures()}）。</li>
 * </ol>
 */
class SdkRouteAckMigrationTest extends MetaDaoTestSupport {

    private static final String MIGRATION = "db/migration/V4__sdk_route_ack.sql";

    /**
     * H2 真跑 V1→V2→V3→V4 全链：V4 必须被 Flyway 执行且成功，列必须真的出现。
     */
    @Test
    @DisplayName("H2 真跑全链：schema_history 里 V4 执行成功，acked_version 列已由引擎建出")
    void should_runFullChainV1ToV4OnH2() {
        List<String> versions = jdbc.queryForList("SELECT version FROM flyway_schema_history "
                + "WHERE version IS NOT NULL ORDER BY installed_rank", String.class);
        assertThat(versions).as("V1→V4 全链必须被迁移引擎执行").startsWith("1", "2", "3", "4");

        Integer v4Applied = jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE version='4' AND success=true AND LOWER(script) LIKE 'v4%'", Integer.class);
        assertThat(v4Applied).as("V4 必须被引擎执行且成功（不是只看脚本在 classpath 里）")
                .isEqualTo(1);

        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                        + "WHERE LOWER(table_name)='cutover_plan' "
                        + "AND LOWER(table_schema) = ?",
                String.class, currentSchema());
        assertThat(columns).as("引擎执行过 V4 的最终证据").contains("acked_version");
    }

    /**
     * 从 information_schema 读回列定义：存在、NOT NULL、BIGINT、默认值含 0。
     */
    @Test
    @DisplayName("列存在且定义正确：BIGINT NOT NULL DEFAULT 0")
    void should_addAckedVersionColumnWithDefault() {
        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                        + "WHERE LOWER(table_name)='cutover_plan' "
                        + "AND LOWER(table_schema) = ?",
                String.class, currentSchema());
        assertThat(columns).contains("acked_version");

        String isNullable = jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns "
                        + "WHERE LOWER(table_name)='cutover_plan' "
                        + "AND LOWER(column_name)='acked_version' "
                        + "AND LOWER(table_schema) = ?",
                String.class, currentSchema());
        assertThat(isNullable).isEqualToIgnoringCase("NO");

        String dataType = jdbc.queryForObject("SELECT data_type FROM information_schema.columns "
                        + "WHERE LOWER(table_name)='cutover_plan' "
                        + "AND LOWER(column_name)='acked_version' "
                        + "AND LOWER(table_schema) = ?",
                String.class, currentSchema());
        assertThat(dataType).isNotNull();
        assertThat(dataType.toUpperCase(Locale.ROOT)).contains("BIGINT");

        String defaultValue = jdbc.queryForObject("SELECT column_default "
                        + "FROM information_schema.columns WHERE LOWER(table_name)='cutover_plan' "
                        + "AND LOWER(column_name)='acked_version' "
                        + "AND LOWER(table_schema) = ?",
                String.class, currentSchema());
        assertThat(defaultValue).isNotNull();
        assertThat(defaultValue.toUpperCase(Locale.ROOT)).contains("0");
    }

    /**
     * 存量行补默认值：按 V1 时代的列集插入（不带新列），读回来必须是 0。
     */
    @Test
    @DisplayName("存量行补默认值：不写该列也能插入，读回来是 0（从未被 ack）")
    void should_backfillDefaultForExistingRows() {
        long id = 9_900_101L;
        long now = System.currentTimeMillis();
        // 模拟 V1 时代的写入方式：列集与 CutoverPlanDao.insert 一致，唯独不带 V4 新增列
        int rows = jdbc.update("INSERT INTO cutover_plan (id,plan_no,task_id,name,intent_state,"
                        + "confirmed_state,route_version,plan_status,gray_dimensions,gray_ratio,"
                        + "write_gray_ratio,gate_compare_pass,gate_lag_p95_ms,gate_fail_rate,"
                        + "gate_dead_letters,created_by,version,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?)",
                id, "CT-V4-BACKFILL-" + id, 9_900_100L, "V4 存量行", "DRAFT", "DRAFT", 0L,
                "ACTIVE", "[]", 0, 0, new BigDecimal("0.999900"), 5000L,
                new BigDecimal("0.000100"), 0L, "migration-test",
                new java.sql.Timestamp(now), new java.sql.Timestamp(now));
        assertThat(rows).as("V1 时代列集的 INSERT 必须成功（新列有默认值兜底）").isEqualTo(1);

        Long acked = jdbc.queryForObject(
                "SELECT acked_version FROM cutover_plan WHERE id=?", Long.class, id);
        assertThat(acked).as("存量计划必须补出 0，不能留 NULL").isEqualTo(0L);
    }

    /**
     * 静态词表检查（防脚本漂移，非兼容性验证）：禁用构造且只做一件事。
     *
     * @throws IOException 读不到脚本
     */
    @Test
    @DisplayName("静态词表检查（防脚本漂移，非兼容性验证）：禁用构造且只做一件事")
    void should_onlyUsePortableSingleAlter() throws IOException {
        String sql = readMigration().toUpperCase(Locale.ROOT);
        String code = stripComments(sql);

        String[] forbidden = {"AFTER ", "ENGINE", "CHARSET", "COLLATE", "COMMENT ", "IF NOT EXISTS",
            "MODIFY ", "CHANGE ", "DEFAULT CURRENT_TIMESTAMP", "AUTO_INCREMENT"};
        for (String token : forbidden) {
            assertThat(code).as("V4 不应出现方言构造：" + token).doesNotContain(token);
        }
        // 只允许一条语句，且是标准 ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT <字面量>
        assertThat(code.split(";", -1)).hasSize(2);
        assertThat(code).contains("ALTER TABLE CUTOVER_PLAN");
        assertThat(code).contains("ADD COLUMN ACKED_VERSION BIGINT");
        assertThat(code).contains("NOT NULL DEFAULT 0");
        String normalized = code.replaceAll("\\s+", " ").trim();
        assertThat(normalized).matches("ALTER TABLE CUTOVER_PLAN ADD COLUMN ACKED_VERSION BIGINT "
                + "NOT NULL DEFAULT '?0'?;");
    }

    /**
     * 迁移历史完整且无失败：版本序列含 1/2/3/4，全部 success=true，无 success=false 记录。
     */
    @Test
    @DisplayName("迁移历史：版本序列含 1/2/3/4，全部成功且无失败记录")
    void should_keepFullHistoryWithoutFailures() {
        List<String> versions = jdbc.queryForList("SELECT version FROM flyway_schema_history "
                + "WHERE version IS NOT NULL ORDER BY installed_rank", String.class);
        assertThat(versions).contains("1", "2", "3", "4");

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
        ClassLoader loader = SdkRouteAckMigrationTest.class.getClassLoader();
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
