package com.dbsync.api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dbsync.api.ApiTestSupport;
import com.dbsync.api.security.CurrentUser;
import com.dbsync.api.security.Role;
import com.dbsync.api.service.MappingService;
import com.dbsync.common.enums.DbType;
import com.dbsync.common.model.DataSourceDef;
import com.dbsync.common.model.TableMapping;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.zaxxer.hikari.HikariConfig;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MAP-05 验收：DDL 差异对比（源结构 / 生成 DDL / 目标库现有结构）。
 *
 * <p>用真实 H2 当"目标库"：差异判定的价值全在"读出来的目标结构对不对、比对规则有没有假阳性"，
 * 把 {@code MetadataReader} mock 掉就什么都验不到。方言走 MYSQL（H2 的 MySQL 兼容模式），
 * 元数据仍由 JDBC 标准接口读取，不依赖任何库专属 SQL。
 *
 * <p>重点用例是 {@link #should_treatNumericAsDecimal_When_onlySynonymDiffers()}：
 * {@code decimal(38,10)} 与 {@code numeric(38,10)} 是同一种类型，判成不一致就是
 * "满屏红条"式假阳性——报告一旦不可信，运维就会整体忽略它。
 */
class MappingDdlDiffTest extends ApiTestSupport {

    private MockMvc mvc;
    private String viewerToken;
    private JdbcDataSource bizDs;
    private long taskId;

    @BeforeEach
    void setUpDiff() {
        bizDs = h2("ddl_diff_biz");
        long srcDsId = newDataSource("ddl-src", DbType.MYSQL);
        long tgtDsId = newDataSource("ddl-tgt", DbType.MYSQL);
        taskId = newTask(srcDsId, tgtDsId);
        DataSourceRegistry registry = new DataSourceRegistry(dataSourceDao, null) {
            @Override
            protected DataSource newPool(DataSourceDef def, HikariConfig config) {
                return bizDs;
            }

            @Override
            protected void ensureDriverAvailable(DataSourceDef def) {
                // 单测：H2 驱动已在 classpath，跳过真实驱动检查
            }
        };
        MappingService service = new MappingService(taskDao, dataSourceDao, mappingDao,
                columnMappingDao, registry, idGenerator);
        mvc = mockMvc(new MappingController(service));
        viewerToken = loginAs("ddl-viewer", Role.VIEWER);
    }

    @Test
    @DisplayName("目标表不存在：targetExists=false、所有列 NEW、不抛异常")
    void should_markAllColumnsNew_When_targetTableMissing() throws Exception {
        execute("CREATE TABLE t_missing (id BIGINT NOT NULL, amt DECIMAL(38,10), "
                + "c_time TIMESTAMP, PRIMARY KEY (id))");
        long mappingId = newMapping("t_missing", "t_missing_target_absent");

        mvc.perform(get("/api/tasks/" + taskId + "/mappings/" + mappingId + "/ddl-diff")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mappingId").value(mappingId))
                .andExpect(jsonPath("$.data.sourceTable").value("t_missing"))
                .andExpect(jsonPath("$.data.targetTable").value("t_missing_target_absent"))
                .andExpect(jsonPath("$.data.targetExists").value(false))
                .andExpect(jsonPath("$.data.columns.length()").value(3))
                .andExpect(jsonPath("$.data.columns[0].status").value("NEW"))
                .andExpect(jsonPath("$.data.columns[1].status").value("NEW"))
                .andExpect(jsonPath("$.data.columns[2].status").value("NEW"))
                .andExpect(jsonPath("$.data.columns[1].name").value("amt"))
                .andExpect(jsonPath("$.data.columns[1].generatedType").value("DECIMAL(38,10)"))
                .andExpect(jsonPath("$.data.summary.newColumns").value(3))
                .andExpect(jsonPath("$.data.summary.typeMismatch").value(0))
                .andExpect(jsonPath("$.data.generatedDdl[0]")
                        .value(org.hamcrest.Matchers.containsString("CREATE TABLE")));
    }

    @Test
    @DisplayName("目标少一列：缺的列标 NEW，其余 OK")
    void should_markNew_When_targetMissesColumn() throws Exception {
        execute("CREATE TABLE t_new_col (id BIGINT NOT NULL, amt DECIMAL(38,10), "
                + "c_time TIMESTAMP, PRIMARY KEY (id))");
        execute("CREATE TABLE t_new_col_target (id BIGINT NOT NULL, amt DECIMAL(38,10))");
        long mappingId = newMapping("t_new_col", "t_new_col_target");

        mvc.perform(get("/api/tasks/" + taskId + "/mappings/" + mappingId + "/ddl-diff")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetExists").value(true))
                .andExpect(jsonPath("$.data.columns[0].name").value("id"))
                .andExpect(jsonPath("$.data.columns[0].status").value("OK"))
                .andExpect(jsonPath("$.data.columns[1].name").value("amt"))
                .andExpect(jsonPath("$.data.columns[1].status").value("OK"))
                .andExpect(jsonPath("$.data.columns[2].name").value("c_time"))
                .andExpect(jsonPath("$.data.columns[2].status").value("NEW"))
                .andExpect(jsonPath("$.data.summary.newColumns").value(1))
                .andExpect(jsonPath("$.data.summary.typeMismatch").value(0));
    }

    @Test
    @DisplayName("目标列类型更窄：TYPE_MISMATCH 且说明写清可能截断")
    void should_markTypeMismatch_When_targetNarrower() throws Exception {
        execute("CREATE TABLE t_narrow (id BIGINT NOT NULL, amt DECIMAL(38,10), "
                + "PRIMARY KEY (id))");
        execute("CREATE TABLE t_narrow_target (id BIGINT NOT NULL, amt DECIMAL(30,6))");
        long mappingId = newMapping("t_narrow", "t_narrow_target");

        mvc.perform(get("/api/tasks/" + taskId + "/mappings/" + mappingId + "/ddl-diff")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.columns[1].name").value("amt"))
                .andExpect(jsonPath("$.data.columns[1].generatedType").value("DECIMAL(38,10)"))
                .andExpect(jsonPath("$.data.columns[1].targetType").value("DECIMAL(30,6)"))
                .andExpect(jsonPath("$.data.columns[1].status").value("TYPE_MISMATCH"))
                .andExpect(jsonPath("$.data.columns[1].note")
                        .value(org.hamcrest.Matchers.containsString("截断")))
                .andExpect(jsonPath("$.data.summary.typeMismatch").value(1));
    }

    @Test
    @DisplayName("防假阳性：decimal(38,10) vs numeric(38,10) 必须判 OK")
    void should_treatNumericAsDecimal_When_onlySynonymDiffers() throws Exception {
        execute("CREATE TABLE t_synonym (id BIGINT NOT NULL, amt DECIMAL(38,10), "
                + "PRIMARY KEY (id))");
        execute("CREATE TABLE t_synonym_target (id BIGINT NOT NULL, amt NUMERIC(38,10))");
        long mappingId = newMapping("t_synonym", "t_synonym_target");

        mvc.perform(get("/api/tasks/" + taskId + "/mappings/" + mappingId + "/ddl-diff")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.columns[1].name").value("amt"))
                // 目标库里这一列确实叫 NUMERIC
                .andExpect(jsonPath("$.data.columns[1].targetType")
                        .value(org.hamcrest.Matchers.startsWith("NUMERIC")))
                // 但归一化后与 DECIMAL(38,10) 等价 —— 不许报不一致
                .andExpect(jsonPath("$.data.columns[1].status").value("OK"))
                .andExpect(jsonPath("$.data.summary.typeMismatch").value(0));
    }

    @Test
    @DisplayName("目标缺索引：MISSING")
    void should_markIndexMissing_When_targetLacksIt() throws Exception {
        execute("CREATE TABLE t_index (id BIGINT NOT NULL, c_time TIMESTAMP, PRIMARY KEY (id))");
        execute("CREATE INDEX idx_c_time ON t_index (c_time)");
        execute("CREATE TABLE t_index_target (id BIGINT NOT NULL, c_time TIMESTAMP)");
        long mappingId = newMapping("t_index", "t_index_target");

        mvc.perform(get("/api/tasks/" + taskId + "/mappings/" + mappingId + "/ddl-diff")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.indexes.length()").value(1))
                .andExpect(jsonPath("$.data.indexes[0].name").value("idx_c_time"))
                .andExpect(jsonPath("$.data.indexes[0].status").value("MISSING"))
                .andExpect(jsonPath("$.data.summary.missingIndexes").value(1));
    }

    @Test
    @DisplayName("summary 计数与 columns / indexes 明细逐项一致（含 EXTRA 列）")
    void should_alignSummaryWithDetails() throws Exception {
        execute("CREATE TABLE t_summary (id BIGINT NOT NULL, amt DECIMAL(38,10), "
                + "c_time TIMESTAMP, PRIMARY KEY (id))");
        execute("CREATE INDEX idx_summary_time ON t_summary (c_time)");
        execute("CREATE TABLE t_summary_target (id BIGINT NOT NULL, amt DECIMAL(30,6), "
                + "audit_by VARCHAR(32))");
        long mappingId = newMapping("t_summary", "t_summary_target");

        mvc.perform(get("/api/tasks/" + taskId + "/mappings/" + mappingId + "/ddl-diff")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                // 源列 3 个 + EXTRA 列 1 个 = 4 条明细
                .andExpect(jsonPath("$.data.columns.length()").value(4))
                .andExpect(jsonPath("$.data.columns[2].name").value("c_time"))
                .andExpect(jsonPath("$.data.columns[2].status").value("NEW"))
                .andExpect(jsonPath("$.data.columns[3].name").value("audit_by"))
                .andExpect(jsonPath("$.data.columns[3].status").value("EXTRA"))
                .andExpect(jsonPath("$.data.columns[3].sourceType")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.summary.newColumns").value(1))
                .andExpect(jsonPath("$.data.summary.typeMismatch").value(1))
                .andExpect(jsonPath("$.data.summary.extraColumns").value(1))
                .andExpect(jsonPath("$.data.summary.missingIndexes").value(1));
    }

    @Test
    @DisplayName("权限：VIEWER 可读；未登录 401")
    void should_requireToken() throws Exception {
        execute("CREATE TABLE t_auth (id BIGINT NOT NULL, PRIMARY KEY (id))");
        long mappingId = newMapping("t_auth", "t_auth_target_absent");

        mvc.perform(get("/api/tasks/" + taskId + "/mappings/" + mappingId + "/ddl-diff")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tasks/" + taskId + "/mappings/" + mappingId + "/ddl-diff"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 造一个表映射（源/目标同 schema，表名不同）。
     *
     * @param sourceTable 源表
     * @param targetTable 目标表
     * @return 映射 ID
     */
    private long newMapping(String sourceTable, String targetTable) {
        long id = idGenerator.nextId();
        mappingDao.insert(new TableMapping(id, taskId, "public", sourceTable, "public", targetTable,
                true, "NUMERIC", 1_000L, null, true, true));
        return id;
    }

    /**
     * 在业务库（H2）里执行一条 DDL。
     *
     * @param sql SQL
     * @throws SQLException 执行失败
     */
    private void execute(String sql) throws SQLException {
        try (Connection connection = bizDs.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * 独立命名的 H2 内存库（每类一个，避免互相污染）。
     *
     * @param name 库名
     * @return 数据源
     */
    private static JdbcDataSource h2(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        ds.setUser("sa");
        ds.setPassword("sa");
        return ds;
    }
}
