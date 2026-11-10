package com.dbsync.api.cdc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dbsync.api.controller.CdcController;
import com.dbsync.api.security.CurrentUser;
import com.dbsync.api.security.Role;
import com.dbsync.api.service.CdcService;
import com.dbsync.api.service.TaskService;
import com.dbsync.cdc.TriggerInstaller;
import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.enums.TaskState;
import com.dbsync.core.port.IncrementalMetricsPort;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 只读运行态端点测试（{@code GET /api/tasks/{taskId}/cdc/runtime}）。
 *
 * <p>验收点：权限（TASK_VIEW 即可读，VIEWER 也能看）、字段名与语义（camelCase）、
 * 运行/未运行两种状态、任务不存在返回结构化 {@code E3004}。
 */
class CdcRuntimeEndpointTest extends CdcRuntimeTestSupport {

    private MockMvc mvc;
    private String viewerToken;

    /**
     * 装配控制器（真实 CdcService + 真实运行期，只 mock 触发器部署器与指标端口）。
     */
    @BeforeEach
    void setUpController() {
        TaskService taskService = new TaskService(taskDao, dataSourceDao, mappingDao, idGenerator,
                runtime);
        CdcService cdcService = new CdcService(taskDao, mappingDao, changeLogDao,
                mock(TriggerInstaller.class), mock(IncrementalMetricsPort.class), taskService,
                runtime);
        mvc = mockMvc(new CdcController(cdcService));
        viewerToken = loginAs("cdc-runtime-viewer", Role.VIEWER);
    }

    @Test
    @DisplayName("未运行：running=false，配置并发照实返回")
    void should_reportNotRunning() throws Exception {
        long taskId = newTask(TaskState.PAUSED, IncrementalMode.TRIGGER_LOG, 2, 6);

        mvc.perform(get("/api/tasks/" + taskId + "/cdc/runtime")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.taskId").value(taskId))
                .andExpect(jsonPath("$.data.running").value(false))
                .andExpect(jsonPath("$.data.anyInstanceConsuming").value(false))
                .andExpect(jsonPath("$.data.consumerConcurrency").value(6))
                .andExpect(jsonPath("$.data.ownerInstance").value(INSTANCE))
                // 字段恒在、值为 null（前端按"字段存在 + 值可空"处理）
                .andExpect(jsonPath("$.data.startedAt").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.lastError").value(Matchers.nullValue()));
    }

    @Test
    @DisplayName("运行中：running=true 且有 startedAt + anyInstanceConsuming=true（租约真实存在）")
    void should_reportRunning() throws Exception {
        long taskId = newTask(TaskState.RUNNING, IncrementalMode.TRIGGER_LOG, 1, 2);
        String table = "t_runtime_api";
        newMapping(taskId, table);
        createTargetTable(table);
        runtime.start(taskId);
        // 组租约由消费槽位异步抢占，视图读之前先等"确有一个未过期租约"，避免竞态
        awaitUntil(() -> watermarkDao.anyLeaseActive(taskId), "组租约已被本实例持有");

        mvc.perform(get("/api/tasks/" + taskId + "/cdc/runtime")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.running").value(true))
                .andExpect(jsonPath("$.data.anyInstanceConsuming").value(true))
                .andExpect(jsonPath("$.data.consumerConcurrency").value(2))
                .andExpect(jsonPath("$.data.startedAt").isNotEmpty());
    }

    @Test
    @DisplayName("两个字段语义分开：租约过期后 anyInstanceConsuming=false（僵尸 owner 不算在消费）")
    void should_reportNotConsuming_When_leaseExpired() throws Exception {
        long taskId = newTask(TaskState.PAUSED, IncrementalMode.TRIGGER_LOG, 1, 1);
        String table = "t_runtime_lease";
        newMapping(taskId, table);
        createTargetTable(table);
        runtime.start(taskId);
        awaitUntil(() -> watermarkDao.anyLeaseActive(taskId), "先确有一个活跃租约");
        runtime.stop(taskId);

        // stop 会释放租约 → 两个字段都为 false
        assertFalse(watermarkDao.anyLeaseActive(taskId), "stop 必须释放组租约");
        mvc.perform(get("/api/tasks/" + taskId + "/cdc/runtime")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.running").value(false))
                .andExpect(jsonPath("$.data.anyInstanceConsuming").value(false));
    }

    @Test
    @DisplayName("任务不存在：结构化 E3004（不是 500）")
    void should_return404_When_taskMissing() throws Exception {
        mvc.perform(get("/api/tasks/99999999/cdc/runtime")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("E3004"));
    }

    @Test
    @DisplayName("无令牌：401（端点受 RBAC 保护）")
    void should_return401_When_anonymous() throws Exception {
        long taskId = newTask(TaskState.PAUSED, IncrementalMode.TRIGGER_LOG, 2, 4);
        mvc.perform(get("/api/tasks/" + taskId + "/cdc/runtime"))
                .andExpect(status().isUnauthorized());
    }
}
