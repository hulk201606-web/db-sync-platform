package com.dbsync.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dbsync.api.ApiTestSupport;
import com.dbsync.api.cdc.CdcTestRuntimes;
import com.dbsync.api.security.CurrentUser;
import com.dbsync.api.security.Role;
import com.dbsync.api.service.TaskService;
import com.dbsync.common.enums.DbType;
import com.dbsync.common.util.JsonUtil;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 任务 API 测试（PRD ORC-01 / T43 验收 5）。
 *
 * <p>核心断言：{@code groupCount} 创建后不可修改（保护不变式 I4）。
 */
class TaskControllerTest extends ApiTestSupport {

    private MockMvc mvc;
    private String operatorToken;
    private String viewerToken;
    private long sourceDsId;
    private long targetDsId;

    @BeforeEach
    void setUpController() {
        TaskService service = new TaskService(taskDao, dataSourceDao, mappingDao, idGenerator,
                CdcTestRuntimes.idle(taskDao));
        mvc = mockMvc(new TaskController(service));
        operatorToken = loginAs("task-operator", Role.OPERATOR);
        viewerToken = loginAs("task-viewer", Role.VIEWER);
        sourceDsId = newDataSource("src-" + getClass().getSimpleName(), DbType.MYSQL);
        targetDsId = newDataSource("tgt-" + getClass().getSimpleName(), DbType.DM);
    }

    @Test
    @DisplayName("OPERATOR 新建任务成功并留审计")
    void should_create_When_operator() throws Exception {
        mvc.perform(post("/api/tasks")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJson(taskBody("t1", 16, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("t1"))
                .andExpect(jsonPath("$.data.state").value("DRAFT"))
                .andExpect(jsonPath("$.data.groupCount").value(16))
                .andExpect(jsonPath("$.data.incrementalMode").value("TRIGGER_LOG"));
        Map<String, String> audit = lastAudit();
        assertEquals("TASK_CREATE", audit.get("action"));
        assertEquals("SUCCESS", audit.get("result"));
    }

    @Test
    @DisplayName("VIEWER 只可读，不可建（403 E8001）")
    void should_allowReadOnly_When_viewer() throws Exception {
        mvc.perform(get("/api/tasks").header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
        mvc.perform(post("/api/tasks")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJson(taskBody("t2", 16, null))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("E8001"))
                .andExpect(jsonPath("$.data.required").value("TASK_EDIT"));
    }

    @Test
    @DisplayName("修改 groupCount 被拒 E1001（保护增量保序分区）")
    void should_reject_When_groupCountChanged() throws Exception {
        long taskId = newTask(sourceDsId, targetDsId);
        Map<String, Object> body = taskBody("t3", 32, taskId);
        mvc.perform(put("/api/tasks/" + taskId)
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJson(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E1001"))
                .andExpect(jsonPath("$.data.current").value(16))
                .andExpect(jsonPath("$.data.requested").value(32));
        Integer groupCount = jdbc.queryForObject(
                "SELECT group_count FROM sync_task WHERE id=?", Integer.class, Long.valueOf(taskId));
        assertEquals(16, groupCount == null ? -1 : groupCount.intValue(), "库中 group_count 不能变");
    }

    @Test
    @DisplayName("同源同目标被拒 E1001；并发 >32 被 Bean Validation 拦下")
    void should_reject_When_invalidRequest() throws Exception {
        Map<String, Object> same = taskBody("t4", 16, null);
        same.put("targetDsId", Long.valueOf(sourceDsId));
        mvc.perform(post("/api/tasks")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJson(same)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E1001"));

        Map<String, Object> tooConcurrent = taskBody("t5", 16, null);
        tooConcurrent.put("concurrency", Integer.valueOf(64));
        mvc.perform(post("/api/tasks")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJson(tooConcurrent)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.fieldErrors.concurrency").exists());
    }

    @Test
    @DisplayName("状态动作：READY 合法；ARCHIVE 缺理由 E8002，补理由后 DRAFT→ARCHIVED 合法")
    void should_actWithReason() throws Exception {
        long readyId = newTask(sourceDsId, targetDsId);
        mvc.perform(post("/api/tasks/" + readyId + "/state")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"READY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("READY"));

        long archiveId = newTask(sourceDsId, targetDsId);
        mvc.perform(post("/api/tasks/" + archiveId + "/state")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ARCHIVE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E8002"));

        mvc.perform(post("/api/tasks/" + archiveId + "/state")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ARCHIVE\",\"reason\":\"项目已迁移完成并验收\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ARCHIVED"));
    }

    @Test
    @DisplayName("非法状态跃迁 E3001（ARCHIVED 不可回 READY）；未知动作 E1001")
    void should_reject_When_illegalTransition() throws Exception {
        long taskId = newTask(sourceDsId, targetDsId);
        mvc.perform(post("/api/tasks/" + taskId + "/state")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ARCHIVE\",\"reason\":\"归档理由足够长\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tasks/" + taskId + "/state")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"READY\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("E3001"))
                .andExpect(jsonPath("$.data.from").value("ARCHIVED"))
                .andExpect(jsonPath("$.data.to").value("READY"));

        long otherId = newTask(sourceDsId, targetDsId);
        mvc.perform(post("/api/tasks/" + otherId + "/state")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"FOO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E1001"));
    }

    @Test
    @DisplayName("删除任务：软删后可查不到；不存在的任务 E3004")
    void should_deleteAndReturnNotFound() throws Exception {
        long taskId = newTask(sourceDsId, targetDsId);
        mvc.perform(delete("/api/tasks/" + taskId).header(CurrentUser.TOKEN_HEADER, operatorToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tasks/" + taskId).header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("E3004"));
        Long hardDeletedCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM sync_task WHERE id=? AND deleted_at IS NOT NULL", Long.class,
                Long.valueOf(taskId));
        assertEquals(1L, hardDeletedCount == null ? 0L : hardDeletedCount.longValue(),
                "应为软删（deleted_at 非空、行仍在）");
    }

    private Map<String, Object> taskBody(String name, int groupCount, Long taskId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("sourceDsId", Long.valueOf(sourceDsId));
        map.put("targetDsId", Long.valueOf(targetDsId));
        map.put("groupCount", Integer.valueOf(groupCount));
        map.put("concurrency", Integer.valueOf(4));
        map.put("batchSize", Integer.valueOf(1000));
        map.put("shardRows", Long.valueOf(2000L));
        map.put("rateLimitRows", Long.valueOf(0L));
        map.put("maxRetry", Integer.valueOf(3));
        map.put("incrementalMode", "TRIGGER_LOG");
        map.put("consumerConcurrency", Integer.valueOf(8));
        if (taskId != null) {
            map.put("taskId", taskId);
        }
        return map;
    }
}
