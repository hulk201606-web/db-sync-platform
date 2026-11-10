package com.dbsync.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.dbsync.common.enums.ShardState;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.model.PrecheckReport;
import com.dbsync.core.throttle.ThrottleRegistry;
import com.dbsync.migration.MigrationEngine;
import com.dbsync.migration.MigrationPrecheck;
import com.dbsync.migration.MigrationProgress;
import com.dbsync.migration.RowCountVerifier;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 迁移 API 测试（T27：7 个端点 + 幂等 + 进度缓存）。
 *
 * <p>用真实 {@link MigrationService}（幂等判断、进度缓存都是它自己的逻辑），
 * 只把"真正连库跑数据"的引擎三件套 mock 掉 —— 那部分由 migration 模块的
 * 38 个测试覆盖。
 */
class MigrationControllerTest extends ApiTestSupport {

    private MockMvc mvc;
    private MigrationEngine engine;
    private MigrationPrecheck precheck;
    private RowCountVerifier verifier;
    private ThrottleRegistry throttleRegistry;
    private String operatorToken;
    private String viewerToken;
    private long taskId;

    @BeforeEach
    void setUpController() {
        engine = mock(MigrationEngine.class);
        precheck = mock(MigrationPrecheck.class);
        verifier = mock(RowCountVerifier.class);
        throttleRegistry = new ThrottleRegistry();
        CdcRuntimeManager cdcRuntime = CdcTestRuntimes.idle(taskDao);
        TaskService taskService = new TaskService(taskDao, dataSourceDao, mappingDao, idGenerator,
                cdcRuntime);
        MigrationService service = new MigrationService(engine, precheck, verifier, taskService,
                cdcRuntime);
        // MIG-05/MIG-06 的两个新服务用真实现（它们只是 DAO 聚合 + 注册表查询）
        mvc = mockMvc(new MigrationController(service,
                new MigrationRateLimitService(taskDao, taskService, throttleRegistry),
                new ShardProgressService(shardDao, mappingDao, taskService)));
        operatorToken = loginAs("mig-operator", Role.OPERATOR);
        viewerToken = loginAs("mig-viewer", Role.VIEWER);
        long src = newDataSource("mig-src-" + getClass().getSimpleName(), DbType.MYSQL);
        long tgt = newDataSource("mig-tgt-" + getClass().getSimpleName(), DbType.DM);
        taskId = newTask(src, tgt);
    }

    @Test
    @DisplayName("预检：返回 pass/blocking/warnings")
    void should_precheck() throws Exception {
        when(precheck.run(taskId)).thenReturn(new PrecheckReport(taskId,
                List.of(), List.of("目标表已存在，将追加写入")));
        mvc.perform(post("/api/tasks/" + taskId + "/precheck")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(taskId))
                .andExpect(jsonPath("$.data.pass").value(true))
                .andExpect(jsonPath("$.data.warnings[0]").exists());
    }

    @Test
    @DisplayName("预检有阻断项时 pass=false")
    void should_reportBlocking_When_precheckFails() throws Exception {
        when(precheck.run(taskId)).thenReturn(new PrecheckReport(taskId,
                List.of("源表无主键，无法分片"), List.of()));
        mvc.perform(post("/api/tasks/" + taskId + "/precheck")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pass").value(false))
                .andExpect(jsonPath("$.data.blocking[0]").exists());
    }

    @Test
    @DisplayName("启动：调用引擎一次、留审计、applied=true")
    void should_start() throws Exception {
        mvc.perform(post("/api/tasks/" + taskId + "/migration/start")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(true))
                .andExpect(jsonPath("$.data.action").value("START"));
        verify(engine, times(1)).start(taskId);
        assertEquals("MIGRATION_START", lastAudit().get("action"));
    }

    @Test
    @DisplayName("暂停幂等：已是 PAUSED 时不再调引擎（applied=false）")
    void should_beIdempotent_When_pauseTwice() throws Exception {
        jdbc.update("UPDATE sync_task SET state=? WHERE id=?", TaskState.PAUSED.name(),
                Long.valueOf(taskId));
        mvc.perform(post("/api/tasks/" + taskId + "/migration/pause")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(false))
                .andExpect(jsonPath("$.data.taskState").value("PAUSED"));
        verify(engine, never()).pause(anyLong());
    }

    @Test
    @DisplayName("恢复幂等：已是 RUNNING 时不再调引擎；取消幂等：已 FAILED 亦然")
    void should_beIdempotent_When_resumeOrCancelTwice() throws Exception {
        jdbc.update("UPDATE sync_task SET state=? WHERE id=?", TaskState.RUNNING.name(),
                Long.valueOf(taskId));
        mvc.perform(post("/api/tasks/" + taskId + "/migration/resume")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(false));
        verify(engine, never()).resume(anyLong());

        jdbc.update("UPDATE sync_task SET state=? WHERE id=?", TaskState.FAILED.name(),
                Long.valueOf(taskId));
        mvc.perform(post("/api/tasks/" + taskId + "/migration/cancel")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(false));
        verify(engine, never()).cancel(anyLong());
    }

    @Test
    @DisplayName("进度读缓存：1 秒内两次查询只打一次库")
    void should_cacheProgress() throws Exception {
        Map<ShardState, Integer> counts = new EnumMap<>(ShardState.class);
        counts.put(ShardState.SUCCESS, Integer.valueOf(1));
        counts.put(ShardState.RUNNING, Integer.valueOf(1));
        when(engine.progress(taskId)).thenReturn(new MigrationProgress(taskId, TaskState.RUNNING,
                2, counts, 500L, 1000L));

        mvc.perform(get("/api/tasks/" + taskId + "/migration/progress")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.percent").value(50.0))
                .andExpect(jsonPath("$.data.totalShards").value(2))
                .andExpect(jsonPath("$.data.shardCounts.SUCCESS").value(1));
        mvc.perform(get("/api/tasks/" + taskId + "/migration/progress")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk());
        verify(engine, times(1)).progress(taskId);
    }

    @Test
    @DisplayName("核数：无差异即 allMatch=true")
    void should_verify() throws Exception {
        when(verifier.verify(taskId)).thenReturn(List.of());
        mvc.perform(post("/api/tasks/" + taskId + "/migration/verify")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allMatch").value(true))
                .andExpect(jsonPath("$.data.diffs").isEmpty());
    }

    @Test
    @DisplayName("VIEWER 可看进度但不可启动（403 E8001）")
    void should_enforcePermission() throws Exception {
        when(engine.progress(taskId)).thenReturn(new MigrationProgress(taskId, TaskState.DRAFT,
                0, new EnumMap<>(ShardState.class), 0L, 0L));
        mvc.perform(get("/api/tasks/" + taskId + "/migration/progress")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tasks/" + taskId + "/migration/start")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.required").value("TASK_EXEC"));
    }

    @Test
    @DisplayName("任务不存在 E3004")
    void should_returnNotFound_When_taskMissing() throws Exception {
        mvc.perform(post("/api/tasks/123456789/migration/start")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("E3004"));
    }
}
