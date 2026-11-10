package com.dbsync.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.dbsync.core.throttle.Throttle;
import com.dbsync.core.throttle.ThrottleRegistry;
import com.dbsync.migration.MigrationEngine;
import com.dbsync.migration.MigrationPrecheck;
import com.dbsync.migration.RowCountVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MIG-05 验收：运行中动态限速 {@code POST /api/tasks/{taskId}/migration/limit}。
 *
 * <p>用真实 {@link MigrationRateLimitService} + 真实 H2 + 真实 RBAC/审计拦截器，
 * 只有"真正跑数据"的引擎三件套用 mock（与 {@link MigrationControllerTest} 一致）。
 *
 * <p>三条硬要求：① 注册表里有实例时必须热更新（applied=true / IMMEDIATE）；
 * ② 没有实例时只落库（applied=false / NEXT_START）；
 * ③ 非法值抛 E1001 且<b>不写库</b>（否则留下"库脏内存净"的分裂状态）。
 */
class MigrationLimitControllerTest extends ApiTestSupport {

    private static final String LIMIT_PATH = "/migration/limit";

    private MockMvc mvc;
    private ThrottleRegistry throttleRegistry;
    private String operatorToken;
    private String viewerToken;
    private long taskId;

    @BeforeEach
    void setUpController() {
        throttleRegistry = new ThrottleRegistry();
        MigrationEngine engine = org.mockito.Mockito.mock(MigrationEngine.class);
        CdcRuntimeManager cdcRuntime = CdcTestRuntimes.idle(taskDao);
        TaskService taskService = new TaskService(taskDao, dataSourceDao, mappingDao, idGenerator,
                cdcRuntime);
        MigrationService service = new MigrationService(engine,
                org.mockito.Mockito.mock(MigrationPrecheck.class),
                org.mockito.Mockito.mock(RowCountVerifier.class), taskService, cdcRuntime);
        mvc = mockMvc(new MigrationController(service,
                new MigrationRateLimitService(taskDao, taskService, throttleRegistry),
                new ShardProgressService(shardDao, mappingDao, taskService)));
        operatorToken = loginAs("limit-operator", Role.OPERATOR);
        viewerToken = loginAs("limit-viewer", Role.VIEWER);
        long src = newDataSource("limit-src", DbType.MYSQL);
        long tgt = newDataSource("limit-tgt", DbType.DM);
        taskId = newTask(src, tgt);
    }

    @Test
    @DisplayName("任务在跑：applied=true、effective=IMMEDIATE，且 worker 用的那个实例速率真的变了")
    void should_hotApply_When_taskRunning() throws Exception {
        // 模拟 TaskRuntimeFactory 已经装配过：注册表里有该任务的限速器（初始 5000/s）
        Throttle live = throttleRegistry.acquire(taskId, 5_000L, 5_000L);
        assertThat(live.readLimit()).isEqualTo(5_000L);

        mvc.perform(post("/api/tasks/" + taskId + LIMIT_PATH)
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType("application/json")
                        .content("{\"rowsPerSec\":10000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(taskId))
                .andExpect(jsonPath("$.data.applied").value(true))
                .andExpect(jsonPath("$.data.readLimit").value(10000))
                .andExpect(jsonPath("$.data.writeLimit").value(10000))
                .andExpect(jsonPath("$.data.effective").value("IMMEDIATE"));

        // 热更新打的是"同一个实例"：不重新 acquire 也能看到新速率
        assertThat(live.readLimit()).isEqualTo(10_000L);
        assertThat(live.writeLimit()).isEqualTo(10_000L);
        // 同时已落库（重启后生效）
        assertThat(rateLimitRows()).isEqualTo(10_000L);
        assertThat(lastAudit().get("action")).isEqualTo("MIGRATION_LIMIT_CHANGE");
    }

    @Test
    @DisplayName("任务未运行：applied=false、effective=NEXT_START，且已落库")
    void should_onlyPersist_When_taskNotRunning() throws Exception {
        mvc.perform(post("/api/tasks/" + taskId + LIMIT_PATH)
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType("application/json")
                        .content("{\"rowsPerSec\":20000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(false))
                .andExpect(jsonPath("$.data.effective").value("NEXT_START"))
                .andExpect(jsonPath("$.data.readLimit").value(20000))
                .andExpect(jsonPath("$.data.writeLimit").value(20000));

        assertThat(rateLimitRows()).isEqualTo(20_000L);
        assertThat(throttleRegistry.activeTaskCount()).isZero();
    }

    @Test
    @DisplayName("分项限速：读写不同值，响应按各自生效值返回")
    void should_applySplitLimits() throws Exception {
        mvc.perform(post("/api/tasks/" + taskId + LIMIT_PATH)
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType("application/json")
                        .content("{\"readRowsPerSec\":30000,\"writeRowsPerSec\":8000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readLimit").value(30000))
                .andExpect(jsonPath("$.data.writeLimit").value(8000));
        // 单列 rate_limit_rows 无法表达分设：落两侧较小值（保守），重启后不会压垮目标库
        assertThat(rateLimitRows()).isEqualTo(8_000L);
    }

    @Test
    @DisplayName("0 表示不限速：热更新后 readLimit/writeLimit 归 0")
    void should_unlimit_When_zero() throws Exception {
        Throttle live = throttleRegistry.acquire(taskId, 5_000L, 5_000L);

        mvc.perform(post("/api/tasks/" + taskId + LIMIT_PATH)
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType("application/json")
                        .content("{\"rowsPerSec\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readLimit").value(0))
                .andExpect(jsonPath("$.data.writeLimit").value(0));

        assertThat(live.readLimit()).isZero();
        assertThat(live.writeLimit()).isZero();
        assertThat(rateLimitRows()).isZero();
    }

    @Test
    @DisplayName("负数：E1001 且不写库")
    void should_rejectNegative_WithoutWriting() throws Exception {
        long before = rateLimitRows();

        mvc.perform(post("/api/tasks/" + taskId + LIMIT_PATH)
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType("application/json")
                        .content("{\"rowsPerSec\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E1001"));

        assertThat(rateLimitRows()).isEqualTo(before);
        assertThat(throttleRegistry.activeTaskCount()).isZero();
    }

    @Test
    @DisplayName("超过上限 100000000：E1001 且不写库")
    void should_rejectTooLarge_WithoutWriting() throws Exception {
        long before = rateLimitRows();

        mvc.perform(post("/api/tasks/" + taskId + LIMIT_PATH)
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType("application/json")
                        .content("{\"rowsPerSec\":100000001}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E1001"));

        assertThat(rateLimitRows()).isEqualTo(before);
    }

    @Test
    @DisplayName("三个字段都没给：E1001 且不写库")
    void should_rejectEmptyRequest() throws Exception {
        long before = rateLimitRows();

        mvc.perform(post("/api/tasks/" + taskId + LIMIT_PATH)
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E1001"));

        assertThat(rateLimitRows()).isEqualTo(before);
    }

    @Test
    @DisplayName("VIEWER 无权改限速（403 TASK_EXEC）")
    void should_forbidViewer() throws Exception {
        mvc.perform(post("/api/tasks/" + taskId + LIMIT_PATH)
                        .header(CurrentUser.TOKEN_HEADER, viewerToken)
                        .contentType("application/json")
                        .content("{\"rowsPerSec\":10000}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.required").value("TASK_EXEC"));
    }

    @Test
    @DisplayName("任务不存在：E3004")
    void should_returnNotFound_When_taskMissing() throws Exception {
        mvc.perform(post("/api/tasks/123456789" + LIMIT_PATH)
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType("application/json")
                        .content("{\"rowsPerSec\":10000}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("E3004"));
    }

    /**
     * 读库里当前 {@code rate_limit_rows}。
     *
     * @return 限速值
     */
    private long rateLimitRows() {
        Long value = jdbc.queryForObject(
                "SELECT rate_limit_rows FROM sync_task WHERE id=?", Long.class, Long.valueOf(taskId));
        return value == null ? -1L : value.longValue();
    }
}
