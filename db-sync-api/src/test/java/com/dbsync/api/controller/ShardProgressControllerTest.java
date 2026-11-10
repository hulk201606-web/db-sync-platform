package com.dbsync.api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dbsync.api.ApiTestSupport;
import com.dbsync.api.cdc.CdcRuntimeManager;
import com.dbsync.api.cdc.CdcTestRuntimes;
import com.dbsync.api.security.CurrentUser;
import com.dbsync.api.security.Role;
import com.dbsync.api.service.MigrationRateLimitService;
import com.dbsync.api.service.MigrationService;
import com.dbsync.api.service.ShardProgressService;
import com.dbsync.api.service.TaskService;
import com.dbsync.common.enums.DbType;
import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.enums.ShardState;
import com.dbsync.common.model.Shard;
import com.dbsync.common.model.TableMapping;
import com.dbsync.core.throttle.ThrottleRegistry;
import com.dbsync.migration.MigrationEngine;
import com.dbsync.migration.MigrationPrecheck;
import com.dbsync.migration.RowCountVerifier;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MIG-06 验收：任务 → 表 → 分片三级进度
 * （{@code GET /shards} 与 {@code GET /tables/progress}）。
 *
 * <p>用真实 DAO + 真实 H2：这两级进度的价值全在 SQL 聚合是否正确
 * （GROUP BY 的映射关系、百分比口径、分页边界），mock 掉 DAO 就什么都验证不到。
 */
class ShardProgressControllerTest extends ApiTestSupport {

    private MockMvc mvc;
    private String viewerToken;
    private String operatorToken;
    private long taskId;
    private long mappingA;
    private long mappingB;

    @BeforeEach
    void setUpController() {
        CdcRuntimeManager cdcRuntime = CdcTestRuntimes.idle(taskDao);
        TaskService taskService = new TaskService(taskDao, dataSourceDao, mappingDao, idGenerator,
                cdcRuntime);
        MigrationService service = new MigrationService(Mockito.mock(MigrationEngine.class),
                Mockito.mock(MigrationPrecheck.class), Mockito.mock(RowCountVerifier.class),
                taskService, cdcRuntime);
        mvc = mockMvc(new MigrationController(service,
                new MigrationRateLimitService(taskDao, taskService, new ThrottleRegistry()),
                new ShardProgressService(shardDao, mappingDao, taskService)));
        viewerToken = loginAs("shard-viewer", Role.VIEWER);
        operatorToken = loginAs("shard-operator", Role.OPERATOR);
        long src = newDataSource("shard-src", DbType.MYSQL);
        long tgt = newDataSource("shard-tgt", DbType.DM);
        taskId = newTask(src, tgt);
        mappingA = newMapping("T_ORDER");
        mappingB = newMapping("T_ITEM");
    }

    @Test
    @DisplayName("分片清单：分页总数正确、字段齐全（不含 last_pk 之外的大字段）")
    void should_listShards() throws Exception {
        insertShard(1001L, mappingA, 0, ShardState.SUCCESS, 500L, "10", "20");
        insertShard(1002L, mappingA, 1, ShardState.RUNNING, 120L, "20", "30");
        insertShard(1003L, mappingB, 2, ShardState.FAILED, 0L, "30", "40");
        jdbc.update("UPDATE sync_shard SET last_error=?,source_rows=? WHERE id=?",
                "ORA-00001: 唯一约束冲突", Long.valueOf(60L), Long.valueOf(1003L));
        jdbc.update("UPDATE sync_shard SET last_pk=? WHERE id=?", "25", Long.valueOf(1002L));

        mvc.perform(get("/api/tasks/" + taskId + "/shards")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(200))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[0].shardId").value(1001))
                .andExpect(jsonPath("$.data.items[0].mappingId").value(mappingA))
                .andExpect(jsonPath("$.data.items[0].shardNo").value(0))
                .andExpect(jsonPath("$.data.items[0].state").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].committedRows").value(500))
                .andExpect(jsonPath("$.data.items[0].pkStart").value("10"))
                .andExpect(jsonPath("$.data.items[0].pkEnd").value("20"))
                .andExpect(jsonPath("$.data.items[1].lastPk").value("25"))
                .andExpect(jsonPath("$.data.items[2].lastError").value("ORA-00001: 唯一约束冲突"))
                .andExpect(jsonPath("$.data.items[2].sourceRows").value(60));
    }

    @Test
    @DisplayName("分片清单：state 过滤生效（大小写不敏感）")
    void should_filterByState() throws Exception {
        insertShard(2001L, mappingA, 0, ShardState.SUCCESS, 500L, "10", "20");
        insertShard(2002L, mappingA, 1, ShardState.SUCCESS, 500L, "20", "30");
        insertShard(2003L, mappingA, 2, ShardState.DEAD, 0L, "30", "40");

        mvc.perform(get("/api/tasks/" + taskId + "/shards").param("state", "success")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].state").value("SUCCESS"));

        mvc.perform(get("/api/tasks/" + taskId + "/shards").param("state", "DEAD")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].shardId").value(2003));
    }

    @Test
    @DisplayName("分片清单：未知 state → E1001（不静默返回全部）")
    void should_rejectUnknownState() throws Exception {
        mvc.perform(get("/api/tasks/" + taskId + "/shards").param("state", "NOT_A_STATE")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E1001"));
    }

    @Test
    @DisplayName("分片清单：size 超上限截断到 1000；size=0/负数归一化到 200；page=0 归一化到 1")
    void should_normalizePaging() throws Exception {
        insertShard(3001L, mappingA, 0, ShardState.SUCCESS, 10L, null, null);

        mvc.perform(get("/api/tasks/" + taskId + "/shards").param("size", "999999")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(ShardProgressService.MAX_PAGE_SIZE))
                .andExpect(jsonPath("$.data.total").value(1));

        mvc.perform(get("/api/tasks/" + taskId + "/shards").param("size", "0")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(ShardProgressService.DEFAULT_PAGE_SIZE));

        mvc.perform(get("/api/tasks/" + taskId + "/shards").param("size", "-5").param("page", "0")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(ShardProgressService.DEFAULT_PAGE_SIZE));
    }

    @Test
    @DisplayName("分片清单：第二页只返回剩余条目（offset 正确）")
    void should_pageThrough() throws Exception {
        insertShard(4001L, mappingA, 0, ShardState.SUCCESS, 10L, null, null);
        insertShard(4002L, mappingA, 1, ShardState.SUCCESS, 10L, null, null);
        insertShard(4003L, mappingA, 2, ShardState.SUCCESS, 10L, null, null);

        mvc.perform(get("/api/tasks/" + taskId + "/shards").param("size", "2").param("page", "2")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].shardId").value(4003));
    }

    @Test
    @DisplayName("按表进度：每表 total/success/committed 正确，percent 按行数计算")
    void should_reportTableProgress() throws Exception {
        // 表 A：2 成功 + 1 失败，已提交 600 / 源 1000
        insertShardWithRows(5001L, mappingA, 0, ShardState.SUCCESS, 300L, 500L);
        insertShardWithRows(5002L, mappingA, 1, ShardState.SUCCESS, 300L, 500L);
        insertShardWithRows(5003L, mappingA, 2, ShardState.FAILED, 0L, 0L);
        // 表 B：1 成功 1 死信，已提交 50 / 源未知（0）→ percent 必须为 null
        insertShardWithRows(5004L, mappingB, 3, ShardState.SUCCESS, 50L, 0L);
        insertShardWithRows(5005L, mappingB, 4, ShardState.DEAD, 0L, 0L);

        mvc.perform(get("/api/tasks/" + taskId + "/tables/progress")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(taskId))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].mappingId").value(mappingA))
                .andExpect(jsonPath("$.data.items[0].sourceTable").value("T_ORDER"))
                .andExpect(jsonPath("$.data.items[0].targetTable").value("T_ORDER"))
                .andExpect(jsonPath("$.data.items[0].totalShards").value(3))
                .andExpect(jsonPath("$.data.items[0].successShards").value(2))
                .andExpect(jsonPath("$.data.items[0].failedShards").value(1))
                .andExpect(jsonPath("$.data.items[0].deadShards").value(0))
                .andExpect(jsonPath("$.data.items[0].committedRows").value(600))
                .andExpect(jsonPath("$.data.items[0].sourceRows").value(1000))
                .andExpect(jsonPath("$.data.items[0].percent").value(60.0))
                .andExpect(jsonPath("$.data.items[0].rowsFromEstimate").value(false))
                .andExpect(jsonPath("$.data.items[1].mappingId").value(mappingB))
                .andExpect(jsonPath("$.data.items[1].totalShards").value(2))
                .andExpect(jsonPath("$.data.items[1].successShards").value(1))
                .andExpect(jsonPath("$.data.items[1].deadShards").value(1))
                .andExpect(jsonPath("$.data.items[1].committedRows").value(50))
                // 表 B 的分片全都 source_rows=0（未知）→ 退回 row_count_estimate=1000
                .andExpect(jsonPath("$.data.items[1].sourceRows").value(1000))
                .andExpect(jsonPath("$.data.items[1].percent").value(5.0))
                .andExpect(jsonPath("$.data.items[1].rowsFromEstimate").value(true));
    }

    @Test
    @DisplayName("按表进度：有实测 source_rows 时 percent 生效且 rowsFromEstimate=false")
    void should_useMeasuredSourceRows_When_present() throws Exception {
        insertShardWithRows(5101L, mappingA, 0, ShardState.SUCCESS, 250L, 500L);
        insertShardWithRows(5102L, mappingA, 1, ShardState.SUCCESS, 250L, 500L);

        mvc.perform(get("/api/tasks/" + taskId + "/tables/progress")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].sourceRows").value(1000))
                .andExpect(jsonPath("$.data.items[0].rowsFromEstimate").value(false))
                .andExpect(jsonPath("$.data.items[0].percent").value(50.0));
    }

    @Test
    @DisplayName("按表进度：分片全为 -1（未采到）且有估算值时用估算值，percent 非空")
    void should_fallBackToEstimate_When_allShardsUnknown() throws Exception {
        // insertShard 不写 source_rows → 保持列默认 -1
        insertShard(5201L, mappingA, 0, ShardState.SUCCESS, 250L, "1", "100");

        mvc.perform(get("/api/tasks/" + taskId + "/tables/progress")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].mappingId").value(mappingA))
                .andExpect(jsonPath("$.data.items[0].committedRows").value(250))
                .andExpect(jsonPath("$.data.items[0].sourceRows").value(1000))
                .andExpect(jsonPath("$.data.items[0].rowsFromEstimate").value(true))
                .andExpect(jsonPath("$.data.items[0].percent").value(25.0));
    }

    @Test
    @DisplayName("按表进度：实测与估算都没有时 percent=null（前端显示占位横线）")
    void should_returnNullPercent_When_noSourceRowsAndNoEstimate() throws Exception {
        long mappingC = newMappingWithEstimate("T_NO_ESTIMATE", -1L);
        insertShard(5301L, mappingC, 9, ShardState.SUCCESS, 10L, "1", "9");

        mvc.perform(get("/api/tasks/" + taskId + "/tables/progress")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[2].mappingId").value(mappingC))
                .andExpect(jsonPath("$.data.items[2].committedRows").value(10))
                .andExpect(jsonPath("$.data.items[2].sourceRows").value(0))
                .andExpect(jsonPath("$.data.items[2].rowsFromEstimate").value(false))
                .andExpect(jsonPath("$.data.items[2].percent").value(Matchers.nullValue()));
    }

    @Test
    @DisplayName("按表进度：没有分片的表也返回（各项 0、percent=null）")
    void should_includeMappingWithoutShards() throws Exception {
        insertShardWithRows(6001L, mappingA, 0, ShardState.SUCCESS, 100L, 400L);

        mvc.perform(get("/api/tasks/" + taskId + "/tables/progress")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[1].mappingId").value(mappingB))
                .andExpect(jsonPath("$.data.items[1].totalShards").value(0))
                .andExpect(jsonPath("$.data.items[1].committedRows").value(0))
                .andExpect(jsonPath("$.data.items[1].rowsFromEstimate").value(false))
                .andExpect(jsonPath("$.data.items[1].percent").value(Matchers.nullValue()));
    }

    @Test
    @DisplayName("按表进度：percent 保留两位小数（412000000/820000000 → 50.24）")
    void should_roundPercentToTwoDecimals() throws Exception {
        insertShardWithRows(7001L, mappingA, 0, ShardState.SUCCESS, 412_000_000L,
                820_000_000L);

        mvc.perform(get("/api/tasks/" + taskId + "/tables/progress")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].committedRows").value(412_000_000))
                .andExpect(jsonPath("$.data.items[0].sourceRows").value(820_000_000))
                .andExpect(jsonPath("$.data.items[0].percent").value(50.24));
    }

    @Test
    @DisplayName("两个只读端点：VIEWER 可读，未登录 401")
    void should_requireToken() throws Exception {
        mvc.perform(get("/api/tasks/" + taskId + "/shards")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tasks/" + taskId + "/tables/progress")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tasks/" + taskId + "/shards"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("任务不存在：E3004（两个端点一致）")
    void should_returnNotFound_When_taskMissing() throws Exception {
        mvc.perform(get("/api/tasks/123456789/shards")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("E3004"));
        mvc.perform(get("/api/tasks/123456789/tables/progress")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("E3004"));
    }

    /**
     * 造一个表映射。
     *
     * @param table 表名（源/目标同名）
     * @return 映射 ID
     */
    private long newMapping(String table) {
        long id = idGenerator.nextId();
        mappingDao.insert(new TableMapping(id, taskId, "PUBLIC", table, "PUBLIC", table, true,
                "NUMERIC", 1_000L, null, true, true));
        return id;
    }

    /**
     * 造一个指定 {@code row_count_estimate} 的表映射（估算值兜底用例用）。
     *
     * @param table    表名（源/目标同名）
     * @param estimate 估算行数（-1 表示未知）
     * @return 映射 ID
     */
    private long newMappingWithEstimate(String table, long estimate) {
        long id = idGenerator.nextId();
        mappingDao.insert(new TableMapping(id, taskId, "PUBLIC", table, "PUBLIC", table, true,
                "NUMERIC", estimate, null, true, true));
        return id;
    }

    /**
     * 插入一个分片（行数默认 0）。
     *
     * @param shardId   分片 ID
     * @param mappingId 映射 ID
     * @param shardNo   分片号
     * @param state     状态
     * @param committed 已提交行数
     * @param pkStart   主键起点
     * @param pkEnd     主键终点
     */
    private void insertShard(long shardId, long mappingId, int shardNo, ShardState state,
                             long committed, String pkStart, String pkEnd) {
        shardDao.insertBatch(List.of(new Shard(shardId, taskId, mappingId, shardNo,
                RangeKind.NUMERIC, pkStart, pkEnd, state, null, null, 0L, 1, 0)));
        jdbc.update("UPDATE sync_shard SET committed_rows=? WHERE id=?",
                Long.valueOf(committed), Long.valueOf(shardId));
    }

    /**
     * 插入一个带行数的分片（用于表级聚合断言）。
     *
     * @param shardId   分片 ID
     * @param mappingId 映射 ID
     * @param shardNo   分片号
     * @param state     状态
     * @param committed 已提交行数
     * @param source    源侧行数
     */
    private void insertShardWithRows(long shardId, long mappingId, int shardNo, ShardState state,
                                     long committed, long source) {
        shardDao.insertBatch(List.of(new Shard(shardId, taskId, mappingId, shardNo,
                RangeKind.NUMERIC, null, null, state, null, null, 0L, 1, 0)));
        jdbc.update("UPDATE sync_shard SET committed_rows=?,source_rows=? WHERE id=?",
                Long.valueOf(committed), Long.valueOf(source), Long.valueOf(shardId));
    }
}
