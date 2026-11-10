package com.dbsync.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dbsync.api.ApiTestSupport;
import com.dbsync.api.alert.AlertService;
import com.dbsync.api.dto.datasource.ConnectionTestView;
import com.dbsync.api.dto.datasource.DataSourceRequest;
import com.dbsync.api.dto.datasource.TableInfoView;
import com.dbsync.api.dto.mapping.MappingView;
import com.dbsync.api.dto.migration.DeadLetterView;
import com.dbsync.api.dto.system.UserView;
import com.dbsync.api.security.CurrentUser;
import com.dbsync.api.security.Role;
import com.dbsync.api.service.DataSourceService;
import com.dbsync.api.service.DeadLetterAdminService;
import com.dbsync.api.service.MappingService;
import com.dbsync.api.service.UserService;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 未覆盖端点的 HTTP 层测试（一期自检 C 方案 P1#11）。
 *
 * <p>基线里这些端点没有 web 层测试，这里用一个合并类按 Controller 分组补齐，重点验证
 * 三件事，而不是只验 HTTP 200：
 * <ol>
 *   <li><b>路由</b>：路径 / HTTP 方法 / 路径变量 / 查询参数绑定是否正确；</li>
 *   <li><b>权限注解</b>：{@code @RequiresPermission} 是否真的生效——用 VIEWER 触发 403，
 *       并断言 {@code data.required} 是预期的权限点（不是笼统的 4xx 或恒真断言）；</li>
 *   <li><b>响应体形状</b>：正路径断言 {@code data} 里的具体字段，含审计导出 CSV 表头、
 *       用户视图不得回显口令字段、以及 {@code /test} 与 {@code /{rule}/test} 的字段差异。</li>
 * </ol>
 *
 * <p>装配复用 {@link ApiTestSupport}：真实 H2 + Flyway + 真实 RBAC/审计拦截器 +
 * standalone MockMvc；Service 一律用 Mockito 替身，不连真实 MySQL / SMTP / 端口。
 * 审计导出特意使用基类里的真实 {@code AuditService}（空库），以便断言 CSV 表头。
 *
 * <p>覆盖矩阵：
 * <ul>
 *   <li>SystemController：用户列表 / 新建用户 / 登出 / 改密（非法参数）/ 审计查询（非法参数）/
 *       审计导出 CSV / 用户管理 403 / 401；</li>
 *   <li>DeadLetterController：列表 / 分组 / 单条重放 / 批量重放 / 丢弃 / 缺 taskId 400 /
 *       403 / 401；</li>
 *   <li>AlertController：test / {rule}test / 403 / 401；</li>
 *   <li>DataSourceController：已存连接测试 / 草稿连接测试 / 表清单 / 单表元数据 / 403 / 401；</li>
 *   <li>MappingController：conflict-policy 正路径（含参数透传 verify）/ 403 / 401。</li>
 * </ul>
 */
class UncoveredEndpointsHttpTest extends ApiTestSupport {

    /** 审计导出 CSV 表头（与 AuditService.exportCsv 一致，防止表头漂移）。 */
    private static final String CSV_HEADER =
            "id,createdAt,actor,actorRole,action,targetType,targetId,targetName,"
                    + "result,errorCode,ip,traceId,paramsDigest\n";

    private MockMvc systemMvc;
    private MockMvc deadLetterMvc;
    private MockMvc alertMvc;
    private MockMvc dataSourceMvc;
    private MockMvc mappingMvc;

    private UserService userService;
    private DeadLetterAdminService deadLetterAdminService;
    private AlertService alertService;
    private DataSourceService dataSourceService;
    private MappingService mappingService;

    private String adminToken;
    private String operatorToken;
    private String viewerToken;

    @BeforeEach
    void setUpControllers() {
        userService = mock(UserService.class);
        // 审计用真实 service（空库即可拿到 CSV 表头）；认证用真实 service 以便 logout 生效。
        systemMvc = mockMvc(new SystemController(authService, userService, auditService));

        deadLetterAdminService = mock(DeadLetterAdminService.class);
        deadLetterMvc = mockMvc(new DeadLetterController(deadLetterAdminService));

        alertService = mock(AlertService.class);
        alertMvc = mockMvc(new AlertController(alertService));

        dataSourceService = mock(DataSourceService.class);
        dataSourceMvc = mockMvc(new DataSourceController(dataSourceService));

        mappingService = mock(MappingService.class);
        mappingMvc = mockMvc(new MappingController(mappingService));

        adminToken = loginAs("http-admin", Role.ADMIN);
        operatorToken = loginAs("http-operator", Role.OPERATOR);
        viewerToken = loginAs("http-viewer", Role.VIEWER);
    }

    // ------------------------------------------------------------------
    // SystemController：用户管理 + 审计导出（安全面）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/system/users：200 + 用户视图字段，且绝不回显口令哈希/明文")
    void should_listUsers_When_admin() throws Exception {
        when(userService.list()).thenReturn(List.of(
                new UserView(9L, "alice", "Alice", "VIEWER", true, null)));

        systemMvc.perform(get("/api/system/users")
                        .header(CurrentUser.TOKEN_HEADER, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data[0].username").value("alice"))
                .andExpect(jsonPath("$.data[0].role").value("VIEWER"))
                .andExpect(jsonPath("$.data[0].enabled").value(true))
                .andExpect(jsonPath("$.data[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data[0].password").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/system/users：200 + 新建用户视图（响应体形状）")
    void should_createUser_When_admin() throws Exception {
        when(userService.create(any())).thenReturn(
                new UserView(10L, "bob", "Bob", "OPERATOR", true, null));

        systemMvc.perform(post("/api/system/users")
                        .header(CurrentUser.TOKEN_HEADER, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"displayName\":\"Bob\","
                                + "\"password\":\"Pass@12345\",\"role\":\"OPERATOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.username").value("bob"))
                .andExpect(jsonPath("$.data.role").value("OPERATOR"))
                .andExpect(jsonPath("$.data.enabled").value(true));
    }

    @Test
    @DisplayName("GET /api/system/audit/export：text/csv + attachment + 固定 CSV 表头")
    void should_exportAuditCsv_When_admin() throws Exception {
        systemMvc.perform(get("/api/system/audit/export")
                        .header(CurrentUser.TOKEN_HEADER, adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("audit.csv")))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith(CSV_HEADER)));
    }

    @Test
    @DisplayName("GET /api/system/audit：VIEWER 可读（权限点是 TASK_VIEW，不是 USER_MANAGE）")
    void should_auditQueryAllowsViewer() throws Exception {
        systemMvc.perform(get("/api/system/audit")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("POST /api/auth/logout：带令牌 200（该端点无权限注解，仅需令牌）")
    void should_logout_When_authenticated() throws Exception {
        String token = loginAs("logout-user", Role.VIEWER);
        systemMvc.perform(post("/api/auth/logout")
                        .header(CurrentUser.TOKEN_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
    }

    @Test
    @DisplayName("POST /api/auth/password：空请求体 400 E1001（新口令不能为空）")
    void should_changePasswordRejectsEmptyBody() throws Exception {
        systemMvc.perform(post("/api/auth/password")
                        .header(CurrentUser.TOKEN_HEADER, adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E1001"));
    }

    @Test
    @DisplayName("GET /api/system/audit?from=非法时间：400 E1001（ISO-8601 解析失败）")
    void should_reject400_When_auditFromInvalid() throws Exception {
        systemMvc.perform(get("/api/system/audit").param("from", "not-a-time")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E1001"));
    }

    @Test
    @DisplayName("GET /api/system/users：VIEWER 403 E8001，required=USER_MANAGE")
    void should_reject403_When_viewerManagesUsers() throws Exception {
        systemMvc.perform(get("/api/system/users")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("E8001"))
                .andExpect(jsonPath("$.data.required").value("USER_MANAGE"))
                .andExpect(jsonPath("$.data.role").value("VIEWER"));
    }

    @Test
    @DisplayName("GET /api/system/users：无令牌 401 E8004")
    void should_reject401_When_noTokenOnUsers() throws Exception {
        systemMvc.perform(get("/api/system/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("E8004"));
    }

    // ------------------------------------------------------------------
    // DeadLetterController：5 个端点
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/deadletters：200 + 死信视图字段（taskId/status/limit 绑定）")
    void should_listDeadLetters_When_operator() throws Exception {
        when(deadLetterAdminService.list(1L, "OPEN", 10)).thenReturn(List.of(
                new DeadLetterView(7L, 1L, 100L, "CDC", "E4010", "boom", "OPEN", 2,
                        "{\"id\":1}", null, null)));

        deadLetterMvc.perform(get("/api/deadletters")
                        .param("taskId", "1").param("status", "OPEN").param("limit", "10")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data[0].id").value(7))
                .andExpect(jsonPath("$.data[0].errorCode").value("E4010"))
                .andExpect(jsonPath("$.data[0].status").value("OPEN"));
    }

    @Test
    @DisplayName("GET /api/deadletters/groups：200 + 错误码分组计数")
    void should_groupDeadLetters_When_operator() throws Exception {
        when(deadLetterAdminService.groups(1L, "OPEN")).thenReturn(Map.of("E4010", 3L));

        deadLetterMvc.perform(get("/api/deadletters/groups")
                        .param("taskId", "1").param("status", "OPEN")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.E4010").value(3));
    }

    @Test
    @DisplayName("POST /api/deadletters/{id}/replay：200 + applied/deadLetterId（路径变量绑定）")
    void should_replaySingle_When_operator() throws Exception {
        when(deadLetterAdminService.replay(eq(7L), anyString())).thenReturn(
                Map.of("deadLetterId", 7L, "applied", Boolean.TRUE));

        deadLetterMvc.perform(post("/api/deadletters/7/replay")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(true))
                .andExpect(jsonPath("$.data.deadLetterId").value(7));
    }

    @Test
    @DisplayName("POST /api/deadletters/replay：200 + 批量汇总 total/applied（请求体绑定）")
    void should_replayBatch_When_operator() throws Exception {
        when(deadLetterAdminService.replayBatch(any(), anyString())).thenReturn(
                Map.of("total", 2, "applied", 2, "failed", 0));

        deadLetterMvc.perform(post("/api/deadletters/replay")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[7,8]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.applied").value(2));
    }

    @Test
    @DisplayName("POST /api/deadletters/{id}/discard：200 + status=DISCARDED（reason 透传）")
    void should_discard_When_operator() throws Exception {
        when(deadLetterAdminService.discard(anyLong(), any(), anyString())).thenReturn(
                Map.of("deadLetterId", 7L, "status", "DISCARDED"));

        deadLetterMvc.perform(post("/api/deadletters/7/discard")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"人工确认可丢弃\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISCARDED"))
                .andExpect(jsonPath("$.data.deadLetterId").value(7));
    }

    @Test
    @DisplayName("GET /api/deadletters 缺 taskId：400 E1001 + parameter=taskId")
    void should_reject400_When_deadLetterMissingTaskId() throws Exception {
        deadLetterMvc.perform(get("/api/deadletters")
                        .header(CurrentUser.TOKEN_HEADER, adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E1001"))
                .andExpect(jsonPath("$.data.parameter").value("taskId"));
    }

    @Test
    @DisplayName("POST /api/deadletters/7/replay：VIEWER 403 E8001，required=DEAD_LETTER_MANAGE")
    void should_reject403_When_viewerReplaysDeadLetter() throws Exception {
        deadLetterMvc.perform(post("/api/deadletters/7/replay")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("E8001"))
                .andExpect(jsonPath("$.data.required").value("DEAD_LETTER_MANAGE"));
    }

    @Test
    @DisplayName("GET /api/deadletters：无令牌 401 E8004")
    void should_reject401_When_noTokenOnDeadLetters() throws Exception {
        deadLetterMvc.perform(get("/api/deadletters").param("taskId", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("E8004"));
    }

    // ------------------------------------------------------------------
    // AlertController：2 个端点
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/system/alerts/test：200 + fired/channels，且确实调用 fire(MANUAL)")
    void should_fireTestAlert_When_admin() throws Exception {
        when(alertService.channels()).thenReturn(EnumSet.of(AlertService.Channel.LOG));

        alertMvc.perform(post("/api/system/alerts/test")
                        .header(CurrentUser.TOKEN_HEADER, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.fired").value(true))
                .andExpect(jsonPath("$.data.channels").value(1))
                .andExpect(jsonPath("$.data.rule").doesNotExist());
        verify(alertService).fire(eq("MANUAL"), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("POST /api/system/alerts/{rule}/test：200 + rule 回显，fire 收到该规则")
    void should_fireRuleAlert_When_admin() throws Exception {
        when(alertService.channels()).thenReturn(EnumSet.of(AlertService.Channel.LOG));

        alertMvc.perform(post("/api/system/alerts/LAG_HIGH/test")
                        .header(CurrentUser.TOKEN_HEADER, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fired").value(true))
                .andExpect(jsonPath("$.data.rule").value("LAG_HIGH"));
        verify(alertService).fire(eq("LAG_HIGH"), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("POST /api/system/alerts/test：VIEWER 403，required=USER_MANAGE")
    void should_reject403_When_viewerFiresAlert() throws Exception {
        alertMvc.perform(post("/api/system/alerts/test")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("E8001"))
                .andExpect(jsonPath("$.data.required").value("USER_MANAGE"));
    }

    @Test
    @DisplayName("POST /api/system/alerts/test：无令牌 401 E8004")
    void should_reject401_When_noTokenOnAlert() throws Exception {
        alertMvc.perform(post("/api/system/alerts/test"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("E8004"));
    }

    // ------------------------------------------------------------------
    // DataSourceController：4 个端点
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/datasources/{id}/test：200 + 连接测试视图字段")
    void should_testSavedDataSource_When_admin() throws Exception {
        when(dataSourceService.test(5L)).thenReturn(
                new ConnectionTestView(true, null, 12L, "8.0.32", null, "连接成功", null));

        dataSourceMvc.perform(post("/api/datasources/5/test")
                        .header(CurrentUser.TOKEN_HEADER, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.serverVersion").value("8.0.32"))
                .andExpect(jsonPath("$.data.latencyMs").value(12));
    }

    @Test
    @DisplayName("POST /api/datasources/test：200 + 草稿配置连接测试结果（请求体绑定）")
    void should_testDraftDataSource_When_admin() throws Exception {
        when(dataSourceService.test(any(DataSourceRequest.class))).thenReturn(
                new ConnectionTestView(true, null, 5L, "8.0.32", null, "ok", null));

        dataSourceMvc.perform(post("/api/datasources/test")
                        .header(CurrentUser.TOKEN_HEADER, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ds\",\"dbType\":\"MYSQL\",\"host\":\"127.0.0.1\","
                                + "\"port\":3306,\"username\":\"u\",\"password\":\"p\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.serverVersion").value("8.0.32"));
    }

    @Test
    @DisplayName("GET /api/datasources/{id}/tables：200 + 表清单项字段")
    void should_listTables_When_admin() throws Exception {
        when(dataSourceService.listTables(5L, "public")).thenReturn(
                List.of(TableInfoView.brief("public", "t_order")));

        dataSourceMvc.perform(get("/api/datasources/5/tables").param("schema", "public")
                        .header(CurrentUser.TOKEN_HEADER, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].table").value("t_order"))
                .andExpect(jsonPath("$.data[0].schema").value("public"));
    }

    @Test
    @DisplayName("GET /api/datasources/{id}/tables/{table}：200 + 单表元数据字段")
    void should_tableMeta_When_admin() throws Exception {
        when(dataSourceService.tableMeta(5L, "public", "t_order")).thenReturn(
                new TableInfoView("public", "t_order", 3, true, 100L, "订单表"));

        dataSourceMvc.perform(get("/api/datasources/5/tables/t_order").param("schema", "public")
                        .header(CurrentUser.TOKEN_HEADER, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.table").value("t_order"))
                .andExpect(jsonPath("$.data.columnCount").value(3))
                .andExpect(jsonPath("$.data.hasPrimaryKey").value(true));
    }

    @Test
    @DisplayName("POST /api/datasources/5/test：VIEWER 403，required=DS_MANAGE")
    void should_reject403_When_viewerTestsDataSource() throws Exception {
        dataSourceMvc.perform(post("/api/datasources/5/test")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("E8001"))
                .andExpect(jsonPath("$.data.required").value("DS_MANAGE"));
    }

    @Test
    @DisplayName("GET /api/datasources/5/tables：无令牌 401 E8004")
    void should_reject401_When_noTokenOnDataSource() throws Exception {
        dataSourceMvc.perform(get("/api/datasources/5/tables"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("E8004"));
    }

    // ------------------------------------------------------------------
    // MappingController：conflict-policy
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PUT conflict-policy：200 + 策略回显，且路径变量/请求体确实透传给 service")
    void should_updateConflictPolicy_When_operator() throws Exception {
        when(mappingService.updateConflictPolicy(1L, 100L, "TARGET_WINS")).thenReturn(
                new MappingView(100L, 1L, "public", "t_order", "public", "t_order",
                        true, "NUMERIC", 12345L, false, true, "TARGET_WINS", null));

        mappingMvc.perform(put("/api/tasks/1/mappings/100/conflict-policy")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conflictPolicy\":\"TARGET_WINS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.conflictPolicy").value("TARGET_WINS"))
                .andExpect(jsonPath("$.data.sourceTable").value("t_order"));
        verify(mappingService).updateConflictPolicy(1L, 100L, "TARGET_WINS");
    }

    @Test
    @DisplayName("PUT conflict-policy：VIEWER 403，required=TASK_EDIT")
    void should_reject403_When_viewerUpdatesConflictPolicy() throws Exception {
        mappingMvc.perform(put("/api/tasks/1/mappings/100/conflict-policy")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conflictPolicy\":\"TARGET_WINS\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("E8001"))
                .andExpect(jsonPath("$.data.required").value("TASK_EDIT"));
    }

    @Test
    @DisplayName("PUT conflict-policy：无令牌 401 E8004")
    void should_reject401_When_noTokenOnConflictPolicy() throws Exception {
        mappingMvc.perform(put("/api/tasks/1/mappings/100/conflict-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conflictPolicy\":\"TARGET_WINS\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("E8004"));
    }
}
