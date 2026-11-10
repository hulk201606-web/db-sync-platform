package com.dbsync.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dbsync.api.ApiTestSupport;
import com.dbsync.api.cdc.CdcRuntimeManager;
import com.dbsync.api.cdc.CdcTestRuntimes;
import com.dbsync.api.security.CurrentUser;
import com.dbsync.api.security.Role;
import com.dbsync.api.service.CdcService;
import com.dbsync.api.service.TaskService;
import com.dbsync.cdc.TriggerInstaller;
import com.dbsync.common.enums.DbType;
import com.dbsync.common.enums.TaskState;
import com.dbsync.core.port.IncrementalMetricsPort;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 增量 API 测试（T32：模式 / 延迟 / 触发器 / 追溯）。
 *
 * <p>真实 {@link CdcService}（模式切换的状态校验与幂等都在它里面），
 * 只 mock 触发器部署器与指标端口 —— 前者要连源库（cdc 模块 54 个测试覆盖），
 * 后者是端口契约。
 */
class CdcControllerTest extends ApiTestSupport {

    private MockMvc mvc;
    private TriggerInstaller installer;
    private IncrementalMetricsPort metricsPort;
    private String operatorToken;
    private String viewerToken;
    private long taskId;

    @BeforeEach
    void setUpController() {
        installer = mock(TriggerInstaller.class);
        metricsPort = mock(IncrementalMetricsPort.class);
        CdcRuntimeManager cdcRuntime = CdcTestRuntimes.idle(taskDao);
        TaskService taskService = new TaskService(taskDao, dataSourceDao, mappingDao, idGenerator,
                cdcRuntime);
        CdcService service = new CdcService(taskDao, mappingDao, changeLogDao, installer,
                metricsPort, taskService, cdcRuntime);
        mvc = mockMvc(new CdcController(service));
        operatorToken = loginAs("cdc-operator", Role.OPERATOR);
        viewerToken = loginAs("cdc-viewer", Role.VIEWER);
        long src = newDataSource("cdc-src-" + getClass().getSimpleName(), DbType.MYSQL);
        long tgt = newDataSource("cdc-tgt-" + getClass().getSimpleName(), DbType.DM);
        taskId = newTask(src, tgt);
    }

    @Test
    @DisplayName("延迟指标：端口取值原样透出")
    void should_latency() throws Exception {
        when(metricsPort.latencyP95(anyLong())).thenReturn(1234L);
        when(metricsPort.backlog(anyLong())).thenReturn(56L);
        when(metricsPort.failRate(anyLong(), any())).thenReturn(0.02d);
        when(metricsPort.lastApplyAt(anyLong())).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        mvc.perform(get("/api/tasks/" + taskId + "/cdc/latency")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("TRIGGER_LOG"))
                .andExpect(jsonPath("$.data.latencyP95Ms").value(1234))
                .andExpect(jsonPath("$.data.backlog").value(56))
                .andExpect(jsonPath("$.data.failRate").value(0.02))
                .andExpect(jsonPath("$.data.lastApplyAt").value("2026-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("端口抛异常也不 500（指标缺失退化为 0）")
    void should_degradeGracefully_When_portThrows() throws Exception {
        when(metricsPort.latencyP95(anyLong())).thenThrow(new IllegalStateException("db down"));
        when(metricsPort.backlog(anyLong())).thenThrow(new IllegalStateException("db down"));
        when(metricsPort.failRate(anyLong(), any())).thenThrow(new IllegalStateException("db down"));
        mvc.perform(get("/api/tasks/" + taskId + "/cdc/latency")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.latencyP95Ms").value(0))
                .andExpect(jsonPath("$.data.failRate").value(0.0));
    }

    @Test
    @DisplayName("切换模式：applied=true 且库中生效；同模式重复切换 applied=false")
    void should_switchMode_thenIdempotent() throws Exception {
        mvc.perform(put("/api/tasks/" + taskId + "/cdc/mode")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"SDK_WRITE\",\"reason\":\"改双写\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.from").value("TRIGGER_LOG"))
                .andExpect(jsonPath("$.data.mode").value("SDK_WRITE"))
                .andExpect(jsonPath("$.data.applied").value(true));
        assertEquals("SDK_WRITE", jdbc.queryForObject(
                "SELECT incremental_mode FROM sync_task WHERE id=?", String.class,
                Long.valueOf(taskId)));
        assertEquals("CDC_MODE_SWITCH", lastAudit().get("action"));

        mvc.perform(put("/api/tasks/" + taskId + "/cdc/mode")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"SDK_WRITE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(false));
    }

    @Test
    @DisplayName("RUNNING 状态不可切模式 E3001（T32 验收 3）")
    void should_rejectModeSwitch_When_running() throws Exception {
        jdbc.update("UPDATE sync_task SET state=? WHERE id=?", TaskState.RUNNING.name(),
                Long.valueOf(taskId));
        mvc.perform(put("/api/tasks/" + taskId + "/cdc/mode")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"TIMESTAMP_VERSION\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("E3001"))
                .andExpect(jsonPath("$.data.state").value("RUNNING"));
        assertEquals("TRIGGER_LOG", jdbc.queryForObject(
                "SELECT incremental_mode FROM sync_task WHERE id=?", String.class,
                Long.valueOf(taskId)), "被拒后模式不能变");
    }

    @Test
    @DisplayName("非法模式 E1001")
    void should_reject_When_modeInvalid() throws Exception {
        mvc.perform(put("/api/tasks/" + taskId + "/cdc/mode")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"BINLOG\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E1001"));
    }

    @Test
    @DisplayName("无启用映射时部署触发器被拒 E1001；卸载返回 0 张")
    void should_installOrUninstall() throws Exception {
        mvc.perform(post("/api/tasks/" + taskId + "/cdc/triggers/install")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E1001"))
                .andExpect(jsonPath("$.data.taskId").value(taskId));

        mvc.perform(delete("/api/tasks/" + taskId + "/cdc/triggers")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.installedCount").value(0));
    }

    @Test
    @DisplayName("VIEWER 不可部署触发器（403），但可查延迟")
    void should_enforcePermission() throws Exception {
        when(metricsPort.latencyP95(anyLong())).thenReturn(0L);
        when(metricsPort.backlog(anyLong())).thenReturn(0L);
        when(metricsPort.failRate(anyLong(), any())).thenReturn(0.0d);
        mvc.perform(get("/api/tasks/" + taskId + "/cdc/latency")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tasks/" + taskId + "/cdc/triggers/install")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.required").value("TASK_EXEC"));
    }

    @Test
    @DisplayName("追溯：缺 pk 参数 400；表不在任务内 E1001")
    void should_trace() throws Exception {
        mvc.perform(get("/api/tasks/" + taskId + "/cdc/changes")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E1001"))
                .andExpect(jsonPath("$.data.parameter").value("pk"));

        mvc.perform(get("/api/tasks/" + taskId + "/cdc/changes")
                        .param("pk", "1")
                        .param("table", "not_exists")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E1001"));
    }
}
