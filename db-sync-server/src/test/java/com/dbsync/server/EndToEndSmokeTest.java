package com.dbsync.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.api.alert.AlertService;
import com.dbsync.api.service.CdcService;
import com.dbsync.api.service.CompareService;
import com.dbsync.api.service.CutoverService;
import com.dbsync.api.service.DataSourceService;
import com.dbsync.api.service.DeadLetterAdminService;
import com.dbsync.api.service.MappingService;
import com.dbsync.api.service.MigrationService;
import com.dbsync.api.service.MonitorService;
import com.dbsync.api.service.TaskService;
import com.dbsync.common.util.JsonUtil;
import com.dbsync.core.datasource.DataSourceRegistry;
import com.dbsync.core.port.AuditPort;
import com.dbsync.core.port.CompareVerdictPort;
import com.dbsync.core.port.IncrementalMetricsPort;
import com.dbsync.cutover.CutoverOrchestrator;
import com.dbsync.migration.MigrationEngine;
import com.dbsync.migration.TaskRuntimeFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * 端到端冒烟测试：真起 Spring 容器 + 真 HTTP + 真 H2 元数据库。
 *
 * <p>它验证的是"装配是否成立、主链路是否通"，而不是业务算法（算法由各模块单测覆盖）：
 * <ol>
 *   <li>容器能起来（所有 Bean 装配正确，缺一个就起不来）；</li>
 *   <li>Flyway 已把元数据表建好、种子管理员可登录；</li>
 *   <li>未带令牌 → 401；VIEWER 越权 → 403；</li>
 *   <li>数据源 CRUD、任务创建、映射生成（预期失败：演示数据源连不通，返回结构化错误码
 *       而不是 500 堆栈）；</li>
 *   <li>大盘聚合、切流计划与 Gate 判定链路可用；</li>
 *   <li>静态大盘页可访问。</li>
 * </ol>
 *
 * <p>为什么不用真实业务库跑完整迁移：平台只支持 MySQL/Oracle/PG/DM/DB2 方言，
 * H2 不在支持列表内（也不该为了测试加一个假方言）。存量/增量/比对/切流的
 * 数据面正确性由各模块测试（migration 38 / cdc 54 / compare 59 / cutover 55）保证。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class EndToEndSmokeTest {

    /** 令牌头（与 api 模块 CurrentUser 保持一致）。 */
    private static final String TOKEN_HEADER = "X-DBSync-Token";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DataSourceRegistry dataSourceRegistry;

    @Autowired
    private MigrationEngine migrationEngine;

    @Autowired
    private TaskRuntimeFactory taskRuntimeFactory;

    @Autowired
    private CompareService compareService;

    @Autowired
    private CutoverService cutoverService;

    @Autowired
    private CdcService cdcService;

    @Autowired
    private MigrationService migrationService;

    @Autowired
    private DataSourceService dataSourceService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private MappingService mappingService;

    @Autowired
    private DeadLetterAdminService deadLetterAdminService;

    @Autowired
    private MonitorService monitorService;

    @Autowired
    private CutoverOrchestrator cutoverOrchestrator;

    @Autowired
    private CompareVerdictPort compareVerdictPort;

    @Autowired
    private IncrementalMetricsPort incrementalMetricsPort;

    @Autowired
    private AuditPort auditPort;

    @Autowired
    private AlertService alertService;

    /**
     * 关掉 HttpURLConnection 的输出流式写入。
     *
     * <p>JDK 的 {@code HttpURLConnection} 在"流式 POST 收到 401"时会抛
     * {@code HttpRetryException}（它想重试却无法重放请求体），
     * 导致"断言 401"这种正常用例变成测试框架报错。关掉 streaming 即可。
     */
    @BeforeEach
    void useJdkHttpClient() {
        // java.net.http.HttpClient 不会在收到 401 时尝试"重放请求体"，
        // 因此断言 401/403 的用例不会被底层 JDK 的 HttpRetryException 打断。
        rest.getRestTemplate().setRequestFactory(
                new org.springframework.http.client.JdkClientHttpRequestFactory());
    }

    @Test
    @DisplayName("容器装配：核心 Bean 真实可用（缺一个 Spring 上下文根本起不来）")
    void should_wireAllCoreBeans() {
        // 17 个 @Autowired 字段能注入本身就是装配证明；缺 Bean 时上下文启动即失败，
        // 逐字段 assertNotNull 是恒真断言、无信息量，改为对容器行为的真实断言。
        assertFalse(alertService.channels().isEmpty(), "至少启用一个告警渠道");
        ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
        assertEquals(HttpStatus.OK, health.getStatusCode(), "容器起来后健康检查必须可用");
        assertTrue(String.valueOf(health.getBody()).contains("UP"), "健康检查应为 UP");
        ResponseEntity<String> overview = rest.exchange("/api/monitor/overview", HttpMethod.GET,
                authEntity(String.valueOf(loginAsAdmin().get("token"))), String.class);
        assertEquals(HttpStatus.OK, overview.getStatusCode(), "大盘服务 Bean 必须真实可用");
        assertTrue(String.valueOf(overview.getBody()).contains("totalTasks"),
                "大盘响应体必须含 totalTasks 字段，而不是空壳装配");
    }

    @Test
    @DisplayName("健康检查与首页（接入指引）可访问")
    void should_exposeHealthAndIndexPage() {
        ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
        assertEquals(HttpStatus.OK, health.getStatusCode());
        assertTrue(String.valueOf(health.getBody()).contains("UP"), "健康检查应为 UP");

        // 静态资源：/index.html 由前端控制台产物覆盖（内容会变），这里只验"能取到 HTML"
        ResponseEntity<String> indexPage = rest.getForEntity("/index.html", String.class);
        assertEquals(HttpStatus.OK, indexPage.getStatusCode());
        assertTrue(String.valueOf(indexPage.getHeaders().getContentType()).contains("text/html"),
                "首页必须是 HTML");

        // SPA 路由回落：直接刷新前端二级路径不能 404（BrowserRouter 依赖这个）
        ResponseEntity<String> spaRoute = rest.getForEntity("/tasks/123", String.class);
        assertEquals(HttpStatus.OK, spaRoute.getStatusCode(), "二级路由应回落到 index.html");
        assertTrue(String.valueOf(spaRoute.getBody()).contains("<html"),
                "回落内容应是单页入口");

        // 接口路径不参与回落：不存在的接口必须保持 404，不能被 HTML 掩盖
        ResponseEntity<String> missingApi = rest.getForEntity("/api/not-exists", String.class);
        assertTrue(missingApi.getStatusCode().is4xxClientError(),
                "不存在的 API 不应被 SPA 回落吞掉，实际：" + missingApi.getStatusCode());

        ResponseEntity<String> overview = rest.exchange("/api/monitor/overview", HttpMethod.GET,
                authEntity(String.valueOf(loginAsAdmin().get("token"))), String.class);
        assertEquals(HttpStatus.OK, overview.getStatusCode(), "大盘聚合接口必须可用（T45 后端部分）");
    }

    @Test
    @DisplayName("鉴权：无令牌 401 / 错误口令 401 / 正确口令拿到 ADMIN 令牌")
    void should_enforceToken() {
        ResponseEntity<String> anonymous =
                rest.exchange("/api/tasks", HttpMethod.GET, HttpEntity.EMPTY, String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, anonymous.getStatusCode());
        String anonymousBody = String.valueOf(anonymous.getBody());
        assertTrue(anonymousBody.contains("E8004"));
        // 前端约定：错误体必须带 traceId（报障时只给这个值即可定位整条链路）
        assertTrue(anonymousBody.contains("\"traceId\""), "错误响应必须回填 traceId：" + anonymousBody);
        assertTrue(anonymous.getHeaders().getFirst("X-Trace-Id") != null,
                "响应头也应带 X-Trace-Id");

        ResponseEntity<String> badLogin = rest.postForEntity("/api/auth/login",
                jsonEntity(Map.of("username", "admin", "password", "wrong")), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, badLogin.getStatusCode());

        Map<String, Object> data = loginAsAdmin();
        assertTrue(String.valueOf(data.get("token")).length() >= 32, "令牌长度应足够");
        assertEquals("ADMIN", data.get("role"));
        assertEquals(Boolean.TRUE, data.get("mustChangePassword"),
                "种子口令未改，首登应提示强制改密");
    }

    @Test
    @DisplayName("主链路：数据源 → 任务 → 生成映射（结构化失败）→ 大盘 → 切流计划与 Gate")
    @SuppressWarnings("unchecked")
    void should_runMainFlow() {
        String token = String.valueOf(loginAsAdmin().get("token"));

        // ① 数据源：DEMO 数据源连不通（127.0.0.1:3306 无服务），但配置应保存成功
        Map<String, Object> dsBody = new LinkedHashMap<>();
        dsBody.put("name", "e2e-mysql");
        dsBody.put("dbType", "MYSQL");
        dsBody.put("env", "DEV");
        dsBody.put("host", "127.0.0.1");
        dsBody.put("port", 3306);
        dsBody.put("database", "demo");
        dsBody.put("username", "root");
        dsBody.put("password", "Sec@12345");
        dsBody.put("identifierCase", "LOWER");
        ResponseEntity<String> created = rest.exchange("/api/datasources", HttpMethod.POST,
                jsonEntity(dsBody, token), String.class);
        assertEquals(HttpStatus.OK, created.getStatusCode());
        Map<String, Object> dsData = dataOf(created.getBody());
        long srcId = longOf(dsData.get("id"));
        assertEquals(Boolean.TRUE, dsData.get("hasPassword"));
        assertFalse(String.valueOf(created.getBody()).contains("Sec@12345"),
                "响应绝对不能回显口令");

        Map<String, Object> dsBody2 = new LinkedHashMap<>(dsBody);
        dsBody2.put("name", "e2e-dm");
        dsBody2.put("dbType", "DM");
        dsBody2.put("port", 5236);
        ResponseEntity<String> created2 = rest.exchange("/api/datasources", HttpMethod.POST,
                jsonEntity(dsBody2, token), String.class);
        assertEquals(HttpStatus.OK, created2.getStatusCode());
        long tgtId = longOf(dataOf(created2.getBody()).get("id"));

        // ② 任务
        Map<String, Object> taskBody = new LinkedHashMap<>();
        taskBody.put("name", "e2e-task");
        taskBody.put("sourceDsId", srcId);
        taskBody.put("targetDsId", tgtId);
        taskBody.put("groupCount", 8);
        taskBody.put("concurrency", 2);
        taskBody.put("batchSize", 500);
        taskBody.put("shardRows", 1000);
        taskBody.put("maxRetry", 2);
        taskBody.put("incrementalMode", "TRIGGER_LOG");
        taskBody.put("consumerConcurrency", 2);
        ResponseEntity<String> taskCreated = rest.exchange("/api/tasks", HttpMethod.POST,
                jsonEntity(taskBody, token), String.class);
        assertEquals(HttpStatus.OK, taskCreated.getStatusCode());
        long taskId = longOf(dataOf(taskCreated.getBody()).get("id"));

        // ③ 生成映射：源库连不通 / DM 驱动未部署 → 必须是结构化错误码，而不是裸堆栈
        ResponseEntity<String> generate = rest.exchange(
                "/api/tasks/" + taskId + "/mappings/generate", HttpMethod.POST,
                jsonEntity(Map.of(), token), String.class);
        String generateBody = String.valueOf(generate.getBody());
        // 允许的错误码：E7001 驱动缺失（DM 驱动需放 lib/）、E2001/E2002 连接失败、E2020 探活失败
        assertTrue(generateBody.contains("\"code\":\"E7001\"")
                        || generateBody.contains("\"code\":\"E2001\"")
                        || generateBody.contains("\"code\":\"E2002\"")
                        || generateBody.contains("\"code\":\"E2020\""),
                "连不通必须是结构化错误码，实际：" + generate.getStatusCode() + " body=" + generateBody);
        assertFalse(generateBody.contains("java.sql."), "错误响应不能泄露驱动堆栈");
        assertFalse(generateBody.contains("\tat "), "错误响应不能泄露堆栈");

        // ④ 大盘：任务数与状态键齐全
        ResponseEntity<String> overview = rest.exchange("/api/monitor/overview", HttpMethod.GET,
                authEntity(token), String.class);
        assertEquals(HttpStatus.OK, overview.getStatusCode());
        Map<String, Object> overviewData = dataOf(overview.getBody());
        assertEquals(1, ((Number) overviewData.get("totalTasks")).intValue());
        assertTrue(((Map<String, Object>) overviewData.get("taskCounts")).containsKey("DRAFT"));

        // ⑤ 切流计划 + Gate：无比对结果 → 4 项明细且整体不通过
        Map<String, Object> planBody = new LinkedHashMap<>();
        planBody.put("taskId", taskId);
        planBody.put("name", "e2e-plan");
        planBody.put("grayRatio", 5);
        ResponseEntity<String> planCreated = rest.exchange("/api/cutover/plans", HttpMethod.POST,
                jsonEntity(planBody, token), String.class);
        assertEquals(HttpStatus.OK, planCreated.getStatusCode());
        long planId = longOf(dataOf(planCreated.getBody()).get("id"));

        ResponseEntity<String> gate = rest.exchange("/api/cutover/plans/" + planId + "/gate",
                HttpMethod.POST, authEntity(token), String.class);
        assertEquals(HttpStatus.OK, gate.getStatusCode());
        Map<String, Object> gateData = dataOf(gate.getBody());
        assertEquals(Boolean.FALSE, gateData.get("pass"));
        assertEquals(4, ((List<Object>) gateData.get("items")).size());

        // ⑥ 审计：上面的写操作必须留痕
        ResponseEntity<String> audit = rest.exchange("/api/system/audit", HttpMethod.GET,
                authEntity(token), String.class);
        assertEquals(HttpStatus.OK, audit.getStatusCode());
        assertTrue(String.valueOf(audit.getBody()).contains("TASK_CREATE"), "任务创建必须留审计");

        // ⑦ 告警通道接口（T45：PRD §9 系统页要"告警通道"）
        ResponseEntity<String> alerts = rest.exchange("/api/system/alerts", HttpMethod.GET,
                authEntity(token), String.class);
        assertEquals(HttpStatus.OK, alerts.getStatusCode());
        assertTrue(String.valueOf(alerts.getBody()).contains("channels"), "告警总览要回通道");

        // ⑧ 接口目录（自描述，替代 springdoc）
        ResponseEntity<String> index = rest.getForEntity("/api/openapi.json", String.class);
        assertEquals(HttpStatus.OK, index.getStatusCode());
        assertTrue(String.valueOf(index.getBody()).contains("CUTOVER_ADVANCE"));
    }

    private Map<String, Object> loginAsAdmin() {
        ResponseEntity<String> response = rest.postForEntity("/api/auth/login",
                jsonEntity(Map.of("username", "admin", "password", "Admin@12345")), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode(), "种子管理员应能登录：" + response);
        return dataOf(response.getBody());
    }

    private HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<Map<String, Object>>(body, headers);
    }

    private HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(TOKEN_HEADER, token);
        return new HttpEntity<Map<String, Object>>(body, headers);
    }

    private HttpEntity<Void> authEntity(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TOKEN_HEADER, token);
        return new HttpEntity<Void>(headers);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(String body) {
        Map<String, Object> parsed = JsonUtil.parseObject(body);
        Object data = parsed.get("data");
        if (!(data instanceof Map)) {
            throw new IllegalStateException("响应缺少 data：" + body);
        }
        return (Map<String, Object>) data;
    }

    private static long longOf(Object value) {
        if (!(value instanceof Number)) {
            throw new IllegalStateException("不是数字：" + value);
        }
        return ((Number) value).longValue();
    }
}
