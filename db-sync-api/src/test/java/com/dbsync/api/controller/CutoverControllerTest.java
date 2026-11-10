package com.dbsync.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dbsync.api.ApiTestSupport;
import com.dbsync.api.security.CurrentUser;
import com.dbsync.api.security.Role;
import com.dbsync.api.service.CutoverService;
import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.enums.DbType;
import com.dbsync.common.enums.EnvType;
import com.dbsync.common.enums.IdentifierCase;
import com.dbsync.common.model.DataSourceDef;
import com.dbsync.common.model.JdbcEndpoint;
import com.dbsync.core.port.CompareVerdictPort;
import com.dbsync.core.port.IncrementalMetricsPort;
import com.dbsync.cutover.CutoverOrchestrator;
import com.dbsync.cutover.CutoverStateMachine;
import com.dbsync.cutover.GateEvaluator;
import com.dbsync.cutover.RouteRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 切流 API 测试（T41：7 个端点 + 跳过 Gate 三件套 + dry-run 不写状态）。
 *
 * <p>真实 {@link CutoverService} + 真实编排器/状态机/路由注册表（I5/I6/I7 全链路），
 * 只 mock 两个取数端口 —— 它们背后的实现（比对/增量指标）由各自模块测试覆盖。
 *
 * <p><b>为什么要有 {@link #autoAck} 线程</b>：编排器在 publish 之后必须等到 SDK ack
 * 才写 confirmed（不变式 I6）。测试里用"轮询当前版本 + ack"的小线程扮演 SDK 网关，
 * 这样 verified 的正是"下发→确认→落库"的真实时序，而不是把 ack 打桩掉。
 */
class CutoverControllerTest extends ApiTestSupport {

    private MockMvc mvc;
    private RouteRegistry routeRegistry;
    private CutoverService cutoverService;
    private String adminToken;
    private String operatorToken;
    private String viewerToken;
    private long taskId;

    @BeforeEach
    void setUpController() {
        CompareVerdictPort verdictPort = mock(CompareVerdictPort.class);
        IncrementalMetricsPort metricsPort = mock(IncrementalMetricsPort.class);
        CutoverStateMachine stateMachine = new CutoverStateMachine();
        routeRegistry = new RouteRegistry();
        GateEvaluator gateEvaluator = new GateEvaluator(verdictPort, metricsPort, deadLetterDao,
                cutoverPlanDao);
        CutoverOrchestrator orchestrator = new CutoverOrchestrator(cutoverPlanDao,
                cutoverStepLogDao, stateMachine, gateEvaluator, routeRegistry);
        cutoverService = new CutoverService(cutoverPlanDao, cutoverStepLogDao, orchestrator,
                gateEvaluator, stateMachine, routeRegistry, taskDao, dataSourceDao, auditService,
                idGenerator);
        mvc = mockMvc(new CutoverController(cutoverService));
        adminToken = loginAs("ct-admin", Role.ADMIN);
        operatorToken = loginAs("ct-operator", Role.OPERATOR);
        viewerToken = loginAs("ct-viewer", Role.VIEWER);

        long src = newDataSource("ct-src-" + getClass().getSimpleName(), DbType.MYSQL);
        long tgt = newDataSource("ct-tgt-" + getClass().getSimpleName(), DbType.POSTGRESQL);
        taskId = newTask(src, tgt);
    }

    @Test
    @DisplayName("创建计划：ADMIN 可建，留审计；VIEWER 403")
    void should_createPlan() throws Exception {
        mvc.perform(post("/api/cutover/plans")
                        .header(CurrentUser.TOKEN_HEADER, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(taskId, "计划A")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planNo").exists())
                .andExpect(jsonPath("$.data.intentState").value("DRAFT"))
                .andExpect(jsonPath("$.data.inconsistent").value(false))
                .andExpect(jsonPath("$.data.thresholds.comparePassRate").value(0.9999));
        assertEquals("CUTOVER_PLAN_CREATE", lastAudit().get("action"));

        mvc.perform(post("/api/cutover/plans")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(taskId, "计划B")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.required").value("CUTOVER_ADVANCE"));
    }

    @Test
    @DisplayName("详情：plan + steps + 路由版本；VIEWER 可读")
    void should_detail() throws Exception {
        long planId = createPlan();
        mvc.perform(get("/api/cutover/plans/" + planId)
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.id").value(planId))
                .andExpect(jsonPath("$.data.steps").isEmpty())
                .andExpect(jsonPath("$.data.routeVersion").value(0))
                .andExpect(jsonPath("$.data.ackedVersion").value(0));
    }

    @Test
    @DisplayName("Gate：无比对结果 → 4 项明细且 pass=false（宁可拦住不可放过）")
    void should_gate() throws Exception {
        long planId = createPlan();
        mvc.perform(post("/api/cutover/plans/" + planId + "/gate")
                        .header(CurrentUser.TOKEN_HEADER, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pass").value(false))
                .andExpect(jsonPath("$.data.items.length()").value(4))
                .andExpect(jsonPath("$.data.items[0].code").value("COMPARE_PASS_RATE"))
                .andExpect(jsonPath("$.data.items[0].pass").value(false))
                .andExpect(jsonPath("$.data.reasons").isNotEmpty());
    }

    @Test
    @DisplayName("推进：DRAFT→PRE_CHECK，路由版本 +1 且 confirmed 跟上（等 ack）")
    void should_advance() throws Exception {
        long planId = createPlan();
        Thread sdk = autoAck(planId);
        mvc.perform(post("/api/cutover/plans/" + planId + "/advance")
                        .header(CurrentUser.TOKEN_HEADER, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fromState").value("DRAFT"))
                .andExpect(jsonPath("$.data.toState").value("PRE_CHECK"))
                .andExpect(jsonPath("$.data.result").value("SUCCESS"));
        sdk.join(2000L);
        assertEquals(CutoverState.PRE_CHECK.name(), planState(planId, "intent_state"));
        assertEquals(CutoverState.PRE_CHECK.name(), planState(planId, "confirmed_state"));
        assertTrue(routeRegistry.currentVersion(planId) > 0L, "应已下发快照");
        assertEquals(routeRegistry.currentVersion(planId), routeRegistry.ackedVersion(planId));
    }

    @Test
    @DisplayName("跳过 Gate：理由 <10 字 → E1001（field=reason）")
    void should_reject_When_reasonTooShort() throws Exception {
        long planId = preparePreCheckPlan(false);
        mvc.perform(post("/api/cutover/plans/" + planId + "/skip-gate")
                        .header(CurrentUser.TOKEN_HEADER, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"太短\",\"confirmText\":\"SKIP-GATE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E1001"))
                .andExpect(jsonPath("$.data.field").value("reason"));
    }

    @Test
    @DisplayName("跳过 Gate：确认文本错误 → E1001（field=confirmText）")
    void should_reject_When_confirmTextWrong() throws Exception {
        long planId = preparePreCheckPlan(false);
        mvc.perform(post("/api/cutover/plans/" + planId + "/skip-gate")
                        .header(CurrentUser.TOKEN_HEADER, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"业务由于窗口期限制必须直接切流\","
                                + "\"confirmText\":\"skip\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E1001"))
                .andExpect(jsonPath("$.data.field").value("confirmText"));
    }

    @Test
    @DisplayName("跳过 Gate：PROD 环境缺 secondConfirmUser → E8002")
    void should_reject_When_prodWithoutSecondConfirm() throws Exception {
        long prodTaskId = prodTask();
        long planId = preparePreCheckPlan(prodTaskId, false);
        mvc.perform(post("/api/cutover/plans/" + planId + "/skip-gate")
                        .header(CurrentUser.TOKEN_HEADER, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"业务由于窗口期限制必须直接切流\","
                                + "\"confirmText\":\"SKIP-GATE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E8002"))
                .andExpect(jsonPath("$.data.env").value("PROD"));
    }

    @Test
    @DisplayName("跳过 Gate：非 ADMIN 被拦（403 E8001，SKIP_GATE 是 ADMIN 专属）")
    void should_reject_When_notAdmin() throws Exception {
        long planId = preparePreCheckPlan(false);
        mvc.perform(post("/api/cutover/plans/" + planId + "/skip-gate")
                        .header(CurrentUser.TOKEN_HEADER, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"业务由于窗口期限制必须直接切流\","
                                + "\"confirmText\":\"SKIP-GATE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("E8001"))
                .andExpect(jsonPath("$.data.required").value("SKIP_GATE"));
    }

    @Test
    @DisplayName("跳过 Gate 成功：GRAY_READ + 步骤 gateSkipped=true + 审计含 4 项实测值")
    void should_recordAudit_When_skipGate() throws Exception {
        long planId = preparePreCheckPlan(false);
        Thread sdk = autoAck(planId);
        mvc.perform(post("/api/cutover/plans/" + planId + "/skip-gate")
                        .header(CurrentUser.TOKEN_HEADER, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"业务由于窗口期限制必须直接切流\","
                                + "\"confirmText\":\"SKIP-GATE\",\"secondConfirmUser\":\"ct-admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toState").value("GRAY_READ"))
                .andExpect(jsonPath("$.data.gateSkipped").value(true))
                .andExpect(jsonPath("$.data.skipReason").value("业务由于窗口期限制必须直接切流"));
        sdk.join(2000L);

        assertEquals(CutoverState.GRAY_READ.name(), planState(planId, "intent_state"));
        // 两层审计：service 写"业务审计"（含 gateItems 实测值），拦截器再写一条
        // 通用成功审计（同 action、更晚落库）。用 gateItems 精确定位业务那条。
        String params = jdbc.queryForObject("SELECT params_digest FROM sys_audit_log "
                + "WHERE action='CUTOVER_SKIP_GATE' AND params_digest LIKE '%gateItems%' "
                + "ORDER BY created_at DESC, id DESC LIMIT 1", String.class);
        assertTrue(params != null, "必须落一条带 gateItems 的 CUTOVER_SKIP_GATE 审计");
        Long rows = jdbc.queryForObject("SELECT COUNT(1) FROM sys_audit_log "
                + "WHERE action='CUTOVER_SKIP_GATE'", Long.class);
        assertEquals(2L, rows == null ? 0L : rows.longValue(),
                "service 审计 + 拦截器审计各一条");
        assertTrue(params.contains("gateItems"), "审计必须带跳过时的 Gate 实测值：" + params);
        assertTrue(params.contains("业务由于窗口期限制必须直接切流"), "审计必须带理由：" + params);
    }

    @Test
    @DisplayName("dry-run 不写任何状态（步骤数/计划状态/路由版本都不变）")
    void should_dryRun_withoutWriting() throws Exception {
        long planId = createPlan();
        long stepsBefore = cutoverStepLogDao.findByPlan(planId).size();
        long versionBefore = routeRegistry.currentVersion(planId);

        mvc.perform(post("/api/cutover/plans/" + planId + "/dry-run")
                        .header(CurrentUser.TOKEN_HEADER, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planId").value(planId))
                .andExpect(jsonPath("$.data.currentState").value("DRAFT"))
                .andExpect(jsonPath("$.data.targetState").value("PRE_CHECK"))
                .andExpect(jsonPath("$.data.allowed").value(true));

        assertEquals(stepsBefore, cutoverStepLogDao.findByPlan(planId).size(), "dry-run 不写步骤");
        assertEquals(CutoverState.DRAFT.name(), planState(planId, "intent_state"));
        assertEquals(versionBefore, routeRegistry.currentVersion(planId), "dry-run 不下发路由");
    }

    @Test
    @DisplayName("回滚：理由 <5 字 → E8002")
    void should_rejectRollback_When_reasonTooShort() throws Exception {
        long planId = createPlan();
        mvc.perform(post("/api/cutover/plans/" + planId + "/rollback")
                        .header(CurrentUser.TOKEN_HEADER, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"错了\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E8002"));
    }

    @Test
    @DisplayName("按任务查计划列表；不存在计划 → E1001")
    void should_listByTask_andNotFound() throws Exception {
        createPlan();
        mvc.perform(get("/api/cutover/plans/by-task/" + taskId)
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/cutover/plans/123456789")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E1001"));
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private long createPlan() throws Exception {
        String body = mvc.perform(post("/api/cutover/plans")
                        .header(CurrentUser.TOKEN_HEADER, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(planBody(taskId, "计划-" + System.nanoTime())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return dataId(body);
    }

    private static long dataId(String body) {
        Map<String, Object> response = com.dbsync.common.util.JsonUtil.parseObject(body);
        Object data = response.get("data");
        if (!(data instanceof Map)) {
            throw new IllegalStateException("响应缺少 data：" + body);
        }
        Object id = ((Map<?, ?>) data).get("id");
        if (!(id instanceof Number)) {
            throw new IllegalStateException("响应缺少 data.id：" + body);
        }
        return ((Number) id).longValue();
    }

    private long preparePreCheckPlan(boolean ack) {
        return preparePreCheckPlan(taskId, ack);
    }

    private long preparePreCheckPlan(long taskIdValue, boolean ack) {
        long planId = cutoverService.create(new com.dbsync.api.dto.cutover.CutoverPlanCreateRequest(
                Long.valueOf(taskIdValue), "计划-" + System.nanoTime(), Integer.valueOf(5),
                Integer.valueOf(0), null, null, null, null), "ct-admin").id();
        seedState(planId, CutoverState.PRE_CHECK);
        return planId;
    }

    private void seedState(long planId, CutoverState state) {
        cutoverPlanDao.updateIntent(planId, state, cutoverPlanDao.versionOf(planId));
        cutoverPlanDao.confirm(planId, state, cutoverPlanDao.routeVersionOf(planId),
                cutoverPlanDao.versionOf(planId));
    }

    private String planState(long planId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM cutover_plan WHERE id=?",
                String.class, Long.valueOf(planId));
    }

    /**
     * 扮演 SDK 网关：轮询发布版本并 ack（I6）。
     *
     * @param planId 计划 ID
     * @return 后台线程
     */
    private Thread autoAck(long planId) {
        Thread thread = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 3000L;
            while (System.currentTimeMillis() < deadline) {
                long version = routeRegistry.currentVersion(planId);
                if (version > 0L && routeRegistry.ackedVersion(planId) < version) {
                    routeRegistry.ack(planId, version);
                    return;
                }
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "sdk-ack-" + planId);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private long prodTask() {
        long prodDsId = idGenerator.nextId();
        DataSourceDef def = DataSourceDef.builder(prodDsId, "prod-" + prodDsId)
                .dbType(DbType.ORACLE)
                .env(EnvType.PROD)
                .endpoint(new JdbcEndpoint("10.0.0.1", 1521, "ORCL", "", Map.of()))
                .username("u")
                .passwordCipher("cipher")
                .keyVersion((short) 1)
                .identifierCase(IdentifierCase.UPPER)
                .poolMaxSize(2)
                .poolMinIdle(1)
                .fetchSize(100)
                .healthStatus("UNKNOWN")
                .build();
        dataSourceDao.insert(def, "test");
        long devTarget = newDataSource("dev-target-" + prodDsId, DbType.DM);
        return newTask(prodDsId, devTarget);
    }

    private static String planBody(long taskIdValue, String name) {
        return "{\"taskId\":" + taskIdValue + ",\"name\":\"" + name
                + "\",\"grayRatio\":5,\"writeGrayRatio\":0}";
    }
}
