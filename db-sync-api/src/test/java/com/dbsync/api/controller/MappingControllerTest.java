package com.dbsync.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dbsync.api.ApiTestSupport;
import com.dbsync.api.dto.mapping.ColumnMappingView;
import com.dbsync.api.dto.mapping.DdlView;
import com.dbsync.api.dto.mapping.MappingDetailView;
import com.dbsync.api.dto.mapping.MappingView;
import com.dbsync.api.security.CurrentUser;
import com.dbsync.api.security.Role;
import com.dbsync.api.service.MappingService;
import com.dbsync.common.model.ColumnMapping;
import com.dbsync.common.model.TableMapping;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 映射 API 测试（T43 / PRD MAP-01~04）。
 *
 * <p>映射生成需要真实连库读元数据（由 metadata 模块的 72 个测试覆盖），
 * 这里只验 web 层：路由、权限、响应结构、审计、R9 风险标签透出。
 */
class MappingControllerTest extends ApiTestSupport {

    private MockMvc mvc;
    private MappingService mappingService;
    private String operatorToken;
    private String viewerToken;

    @BeforeEach
    void setUpController() {
        mappingService = mock(MappingService.class);
        mvc = mockMvc(new MappingController(mappingService));
        operatorToken = loginAs("map-operator", Role.OPERATOR);
        viewerToken = loginAs("map-viewer", Role.VIEWER);
    }

    @Test
    @DisplayName("列表：VIEWER 可读")
    void should_list() throws Exception {
        when(mappingService.list(anyLong())).thenReturn(List.of(MappingView.of(mapping())));
        mvc.perform(get("/api/tasks/1/mappings").header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sourceTable").value("t_order"))
                .andExpect(jsonPath("$.data[0].rangeKind").value("NUMERIC"));
    }

    @Test
    @DisplayName("生成映射：OPERATOR 可执行、留审计、返回条数提示")
    void should_generate() throws Exception {
        when(mappingService.generate(eq(1L), any())).thenReturn(List.of(MappingView.of(mapping())));
        mvc.perform(post("/api/tasks/1/mappings/generate")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tables\":[\"t_order\"],\"overwrite\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("已生成 1 张表映射"))
                .andExpect(jsonPath("$.data[0].targetTable").value("t_order"));
        assertEquals("MAPPING_GENERATE", lastAudit().get("action"));
    }

    @Test
    @DisplayName("VIEWER 不可生成映射（403 E8001）")
    void should_rejectGenerate_When_viewer() throws Exception {
        mvc.perform(post("/api/tasks/1/mappings/generate")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.required").value("TASK_EDIT"));
    }

    @Test
    @DisplayName("详情：mapping + columns 两段")
    void should_detail() throws Exception {
        when(mappingService.detail(anyLong(), anyLong())).thenReturn(new MappingDetailView(
                MappingView.of(mapping()), List.of(ColumnMappingView.of(column(null)))));
        mvc.perform(get("/api/tasks/1/mappings/100")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mapping.id").value(100))
                .andExpect(jsonPath("$.data.columns[0].sourceColumn").value("amount"));
    }

    @Test
    @DisplayName("DDL 预览：透出 Oracle NUMBER 风险标签 R9")
    void should_ddlWithRiskTag() throws Exception {
        when(mappingService.ddl(anyLong(), anyLong())).thenReturn(new DdlView(100L, "t_order",
                "t_order", "CREATE TABLE \"t_order\" (\"amount\" DECIMAL(38,10))",
                List.of("CREATE INDEX idx ON \"t_order\" (\"id\")"), List.of(),
                List.of("R9-NUMBER-38-10")));
        mvc.perform(get("/api/tasks/1/mappings/100/ddl")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createTable").value(
                        org.hamcrest.Matchers.containsString("DECIMAL(38,10)")))
                .andExpect(jsonPath("$.data.riskTags[0]").value("R9-NUMBER-38-10"));
    }

    @Test
    @DisplayName("人工覆盖列类型：override_by 落操作人并留审计")
    void should_overrideColumn() throws Exception {
        when(mappingService.overrideColumn(anyLong(), anyLong(), anyString(), any(), anyString()))
                .thenReturn(ColumnMappingView.of(column("map-operator")));
        mvc.perform(put("/api/tasks/1/mappings/100/columns/amount")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mappedType\":\"DECIMAL(38,10)\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.overrideBy").value("map-operator"))
                .andExpect(jsonPath("$.data.effectiveType").value("DECIMAL(38,10)"));
        verify(mappingService).overrideColumn(eq(1L), eq(100L), eq("amount"), any(),
                eq("map-operator"));
        assertEquals("MAPPING_OVERRIDE_COLUMN", lastAudit().get("action"));
    }

    private static TableMapping mapping() {
        return new TableMapping(100L, 1L, "public", "t_order", "public", "t_order", true,
                "NUMERIC", 12_345L, "CREATE TABLE \"t_order\" (\"id\" BIGINT)", false, true);
    }

    private static ColumnMapping column(String overrideBy) {
        return new ColumnMapping("amount", "amount", "NUMBER", "DECIMAL(38,10)",
                overrideBy == null ? null : "DECIMAL(38,10)", overrideBy, false, false, 2);
    }
}
