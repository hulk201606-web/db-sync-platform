package com.dbsync.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dbsync.api.ApiTestSupport;
import com.dbsync.api.security.CurrentUser;
import com.dbsync.api.security.Role;
import com.dbsync.api.service.CompareService;
import com.dbsync.common.enums.CompareVerdict;
import com.dbsync.common.enums.DbType;
import com.dbsync.common.model.CompareResult;
import com.dbsync.compare.CompareEngine;
import com.dbsync.compare.CompareReportBuilder;
import com.dbsync.compare.DiffRepairer;
import com.dbsync.compare.ReportFile;
import com.dbsync.compare.RepairResult;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 比对 API 测试（T37：9 个端点 / 权限 / CSV / 反向修复双人）。
 *
 * <p>真实 {@link CompareService} + 真实 DAO；只 mock 需要连业务库的
 * {@link CompareEngine} / {@link DiffRepairer} / {@link CompareReportBuilder}。
 */
class CompareControllerTest extends ApiTestSupport {

    private MockMvc mvc;
    private CompareEngine engine;
    private DiffRepairer repairer;
    private CompareReportBuilder reportBuilder;
    private String adminToken;
    private String operatorToken;
    private String viewerToken;
    private long taskId;
    private long jobId;

    @BeforeEach
    void setUpController() throws Exception {
        engine = mock(CompareEngine.class);
        repairer = mock(DiffRepairer.class);
        reportBuilder = mock(CompareReportBuilder.class);
        CompareService service = new CompareService(engine, compareJobDao, compareDiffDao,
                compareBlockDao, repairer, reportBuilder, taskDao,
                name -> "cmp-admin".equals(name));
        mvc = mockMvc(new CompareController(service));
        adminToken = loginAs("cmp-admin", Role.ADMIN);
        operatorToken = loginAs("cmp-operator", Role.OPERATOR);
        viewerToken = loginAs("cmp-viewer", Role.VIEWER);

        long src = newDataSource("cmp-src-" + getClass().getSimpleName(), DbType.MYSQL);
        long tgt = newDataSource("cmp-tgt-" + getClass().getSimpleName(), DbType.DM);
        taskId = newTask(src, tgt);
        jobId = idGenerator.nextId();
        compareJobDao.insert(jobId, taskId, null, "MANUAL", 1000, 8, "{}", "test");
    }

    @Test
    @DisplayName("发起比对：返回 passRate 与 verdict，并留审计")
    void should_run() throws Exception {
        long newJobId = idGenerator.nextId();
        when(engine.run(eq(taskId), any(), any())).thenReturn(new CompareResult(newJobId, taskId,
                3, 1, 300, 2, CompareVerdict.WARN, List.of()));
        mvc.perform(post("/api/tasks/" + taskId + "/compare")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockRows\":1000,\"parallelism\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(newJobId))
                .andExpect(jsonPath("$.data.verdict").value("WARN"))
                .andExpect(jsonPath("$.data.totalBlocks").value(3));
        assertEquals("COMPARE_RUN", lastAudit().get("action"));
    }

    @Test
    @DisplayName("阈值非法（>1）被拒 E1001")
    void should_reject_When_thresholdInvalid() throws Exception {
        mvc.perform(post("/api/tasks/" + taskId + "/compare")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passThreshold\":1.5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E1001"));
        verify(engine, times(0)).run(anyLong(), any(), any());
    }

    @Test
    @DisplayName("作业详情 / 差异分页 / 概览：均可只读访问")
    void should_queryJob() throws Exception {
        mvc.perform(get("/api/compare/" + jobId).header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(jobId))
                .andExpect(jsonPath("$.data.triggerType").value("MANUAL"));

        mvc.perform(get("/api/compare/" + jobId + "/diffs")
                        .param("page", "1").param("size", "50")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.total").value(0));

        mvc.perform(get("/api/compare/" + jobId + "/summary")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.job.jobId").value(jobId))
                .andExpect(jsonPath("$.data.matchedBlocks").value(0));
    }

    @Test
    @DisplayName("作业不存在 E1001")
    void should_returnNotFound_When_jobMissing() throws Exception {
        mvc.perform(get("/api/compare/987654321").header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E1001"));
    }

    @Test
    @DisplayName("报告与 CSV 导出：Content-Type=text/csv + 附件头")
    void should_exportCsv() throws Exception {
        Path csv = Files.createTempFile("compare-" + jobId + "-", ".csv");
        Files.write(csv, "id,type\n1,MISSING_IN_TARGET\n".getBytes(StandardCharsets.UTF_8));
        when(reportBuilder.build(anyLong(), any(), any())).thenReturn(new ReportFile(jobId,
                null, csv.toString(), "<html>ok</html>", Map.of()));

        mvc.perform(get("/api/compare/" + jobId + "/report")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.html").value("<html>ok</html>"));

        mvc.perform(get("/api/compare/" + jobId + "/report/export")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"compare-" + jobId + ".csv\""))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("MISSING_IN_TARGET")));
        File file = csv.toFile();
        assertTrue(file.delete(), "临时 CSV 应可删除");
    }

    @Test
    @DisplayName("回放到目标：OPERATOR 可用、幂等结果透出")
    void should_replay() throws Exception {
        when(repairer.replayToTarget(eq(jobId), anyList()))
                .thenReturn(new RepairResult(jobId, 2, 2, 0, 0, null, List.of(), List.of()));
        mvc.perform(post("/api/compare/" + jobId + "/repair/replay")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"diffIds\":[1,2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(2))
                .andExpect(jsonPath("$.data.success").value(true));
    }

    @Test
    @DisplayName("反向修复：OPERATOR 被权限拦（403 E8001）")
    void should_rejectFixSource_When_notAdmin() throws Exception {
        mvc.perform(post("/api/compare/" + jobId + "/repair/fix-source")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"diffIds\":[1],\"reason\":\"人工确认源库数据错误\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("E8001"))
                .andExpect(jsonPath("$.data.required").value("COMPARE_FIX_SOURCE"));
        verify(repairer, times(0)).fixToSource(anyLong(), anyList(), anyString(), anyString());
    }

    @Test
    @DisplayName("反向修复：ADMIN 但理由 <10 字 → E8002；理由足够则执行")
    void should_fixSource_When_admin() throws Exception {
        mvc.perform(post("/api/compare/" + jobId + "/repair/fix-source")
                        .header(CurrentUser.TOKEN_HEADER, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"diffIds\":[1],\"reason\":\"太短\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E8002"));

        when(repairer.fixToSource(eq(jobId), anyList(), anyString(), anyString()))
                .thenReturn(new RepairResult(jobId, 1, 1, 0, 0, "bak_diff_" + jobId,
                        List.of(), List.of()));
        mvc.perform(post("/api/compare/" + jobId + "/repair/fix-source")
                        .header(CurrentUser.TOKEN_HEADER, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"diffIds\":[1],\"reason\":\"业务确认源库该行被误改，需回写\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.backupTable").value("bak_diff_" + jobId));
        assertEquals("COMPARE_REPAIR_FIX_SOURCE", lastAudit().get("action"));
    }

    @Test
    @DisplayName("取消作业：调用引擎并留审计；VIEWER 不可取消")
    void should_cancel() throws Exception {
        mvc.perform(post("/api/compare/" + jobId + "/cancel")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken))
                .andExpect(status().isOk());
        verify(engine, times(1)).cancel(jobId);
        assertEquals("COMPARE_CANCEL", lastAudit().get("action"));

        mvc.perform(post("/api/compare/" + jobId + "/cancel")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isForbidden());
    }
}
