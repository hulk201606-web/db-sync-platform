package com.dbsync.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dbsync.api.ApiTestSupport;
import com.dbsync.api.security.CurrentUser;
import com.dbsync.api.security.Role;
import com.dbsync.api.service.SdkRouteService;
import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.enums.DbType;
import com.dbsync.common.model.CutoverPlan;
import com.dbsync.common.options.GateThresholds;
import com.dbsync.cutover.RouteRegistry;
import com.dbsync.cutover.RouteRuleEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 业务侧 SDK 路由通道测试（PRD CUT-01：变更广播 ≤3 秒生效 + ack 闭环）。
 *
 * <p>覆盖点：
 * <ol>
 *   <li><b>无计划不 404</b>：返回 {@code routeVersion=0 / state=NONE / 全源} 兜底；</li>
 *   <li><b>数据来源是 confirmed 而非 intent</b>：intent=FULL_TARGET、confirmed=GRAY_READ 时，
 *       下发的是灰度读数——这是两段式状态机的核心约束（I5/I7）；</li>
 *   <li><b>写目标三态</b>：{@code SOURCE / BOTH / TARGET} 分别对应未切、灰度双写、全量切完；</li>
 *   <li><b>ack 单调</b>：v5 之后 ack v3 → {@code applied=false} 且库值仍是 5；</li>
 *   <li><b>鉴权</b>：无令牌 401（SDK 必须用运维配置的服务令牌访问）。</li>
 * </ol>
 */
class SdkRouteControllerTest extends ApiTestSupport {

    private MockMvc mvc;
    private RouteRegistry routeRegistry;
    private String viewerToken;
    private long taskId;
    private long planId;

    @BeforeEach
    void setUpController() {
        RouteRuleEngine ruleEngine = new RouteRuleEngine();
        routeRegistry = new RouteRegistry();
        SdkRouteService service = new SdkRouteService(cutoverPlanDao, ruleEngine, routeRegistry);
        mvc = mockMvc(new SdkRouteController(service));
        viewerToken = loginAs("sdk-viewer", Role.VIEWER);

        long src = newDataSource("sdk-src", DbType.MYSQL);
        long tgt = newDataSource("sdk-tgt", DbType.POSTGRESQL);
        taskId = newTask(src, tgt);
    }

    @Test
    @DisplayName("无切流计划：200 + 全源兜底（routeVersion=0 / state=NONE / 不 404）")
    void should_returnFallback_When_noPlan() throws Exception {
        mvc.perform(get("/api/sdk/route-snapshot").param("taskId", String.valueOf(taskId))
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.taskId").value(taskId))
                .andExpect(jsonPath("$.data.routeVersion").value(0))
                .andExpect(jsonPath("$.data.state").value("NONE"))
                .andExpect(jsonPath("$.data.readFrom").value("SOURCE"))
                .andExpect(jsonPath("$.data.writeTo").value("SOURCE"))
                .andExpect(jsonPath("$.data.grayRatio").value(0))
                .andExpect(jsonPath("$.data.rules").isEmpty())
                .andExpect(jsonPath("$.data.snapshotTtlMs").value(3000))
                .andExpect(jsonPath("$.data.generatedAt").exists());
    }

    @Test
    @DisplayName("GRAY_WRITE：readFrom=TARGET、writeTo=BOTH、grayRatio 取写灰度、规则可读")
    void should_returnGrayWrite_When_confirmedGrayWrite() throws Exception {
        insertPlan(CutoverState.GRAY_WRITE, CutoverState.GRAY_WRITE, 7L, 5, 7);

        mvc.perform(get("/api/sdk/route-snapshot").param("taskId", String.valueOf(taskId))
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routeVersion").value(7))
                .andExpect(jsonPath("$.data.state").value("GRAY_WRITE"))
                .andExpect(jsonPath("$.data.readFrom").value("TARGET"))
                .andExpect(jsonPath("$.data.writeTo").value("BOTH"))
                .andExpect(jsonPath("$.data.grayRatio").value(7))
                .andExpect(jsonPath("$.data.rules[0].dim").value("RATIO"))
                .andExpect(jsonPath("$.data.rules[0].expr").value("hash(pk)%100 < 7"))
                .andExpect(jsonPath("$.data.rules[0].ratio").value(7));
    }

    @Test
    @DisplayName("FULL_TARGET：writeTo=TARGET、grayRatio=100（停写源库）")
    void should_returnFullTarget_When_confirmedFullTarget() throws Exception {
        insertPlan(CutoverState.FULL_TARGET, CutoverState.FULL_TARGET, 12L, 100, 100);

        mvc.perform(get("/api/sdk/route-snapshot").param("taskId", String.valueOf(taskId))
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("FULL_TARGET"))
                .andExpect(jsonPath("$.data.readFrom").value("TARGET"))
                .andExpect(jsonPath("$.data.writeTo").value("TARGET"))
                .andExpect(jsonPath("$.data.grayRatio").value(100));
    }

    @Test
    @DisplayName("intent≠confirmed 时按 confirmed 下发（灰度只到 GRAY_READ）")
    void should_followConfirmed_When_intentAhead() throws Exception {
        insertPlan(CutoverState.FULL_TARGET, CutoverState.GRAY_READ, 3L, 20, 0);

        mvc.perform(get("/api/sdk/route-snapshot").param("taskId", String.valueOf(taskId))
                        .header(CurrentUser.TOKEN_HEADER, viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("GRAY_READ"))
                .andExpect(jsonPath("$.data.readFrom").value("TARGET"))
                .andExpect(jsonPath("$.data.writeTo").value("SOURCE"))
                .andExpect(jsonPath("$.data.grayRatio").value(20));
    }

    @Test
    @DisplayName("ack 单调：v5 后 ack v3 → applied=false 且库值仍为 5")
    void should_ignoreStaleAck_When_versionRegresses() throws Exception {
        insertPlan(CutoverState.GRAY_WRITE, CutoverState.GRAY_WRITE, 5L, 5, 5);

        mvc.perform(post("/api/sdk/route-ack")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":" + taskId
                                + ",\"instanceId\":\"order-svc-1\",\"ackedVersion\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(true))
                .andExpect(jsonPath("$.data.ackedVersion").value(5))
                .andExpect(jsonPath("$.data.note").value("APPLIED"));
        assertEquals(5L, cutoverPlanDao.ackedVersionOf(planId));

        mvc.perform(post("/api/sdk/route-ack")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":" + taskId
                                + ",\"instanceId\":\"order-svc-2\",\"ackedVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(false))
                .andExpect(jsonPath("$.data.note").value("STALE_ACK"))
                .andExpect(jsonPath("$.data.previousVersion").value(5));
        assertEquals(5L, cutoverPlanDao.ackedVersionOf(planId), "回退的 ack 不得改写库值");
    }

    @Test
    @DisplayName("ack 无计划：applied=false / note=NO_PLAN（不报错）")
    void should_notApplyAck_When_noPlan() throws Exception {
        mvc.perform(post("/api/sdk/route-ack")
                        .header(CurrentUser.TOKEN_HEADER, viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":" + taskId
                                + ",\"instanceId\":\"order-svc-1\",\"ackedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applied").value(false))
                .andExpect(jsonPath("$.data.note").value("NO_PLAN"))
                .andExpect(jsonPath("$.data.planId").value(0));
    }

    @Test
    @DisplayName("鉴权：无令牌 401（SDK 必须带运维配置的服务令牌）")
    void should_reject401_When_noToken() throws Exception {
        mvc.perform(get("/api/sdk/route-snapshot").param("taskId", String.valueOf(taskId)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("E8004"));
    }

    /**
     * 直接落一个计划（不走 API，避免测试依赖切流编排器的推进时序）。
     *
     * @param intent    意图状态
     * @param confirmed 已确认状态
     * @param version   路由版本
     * @param readRatio 读灰度
     * @param writeRatio 写灰度
     */
    private void insertPlan(CutoverState intent, CutoverState confirmed, long version,
                            int readRatio, int writeRatio) {
        planId = idGenerator.nextId();
        CutoverPlan plan = new CutoverPlan(planId, "CT-SDK-" + planId, taskId, "SDK 通道测试",
                intent, confirmed, version, "ACTIVE", GateThresholds.defaults(), readRatio,
                writeRatio);
        cutoverPlanDao.insert(plan, "[]", "test");
    }
}
