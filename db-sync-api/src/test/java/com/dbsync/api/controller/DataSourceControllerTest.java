package com.dbsync.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dbsync.api.ApiTestSupport;
import com.dbsync.api.security.CurrentUser;
import com.dbsync.api.security.Role;
import com.dbsync.api.service.DataSourceService;
import com.dbsync.common.enums.DbType;
import com.dbsync.common.util.AesGcmCipher;
import com.dbsync.core.datasource.DataSourceRegistry;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 数据源 API 测试（T43 / PRD DS-01~05）。
 *
 * <p>用真实 {@link DataSourceService} + 真实 DAO + 真实 AES-GCM 加密器
 * （不做 mock），因此"口令密文落库、响应不回显"是端到端验证的。
 */
class DataSourceControllerTest extends ApiTestSupport {

    private MockMvc mvc;
    private String adminToken;

    @BeforeEach
    void setUpController() {
        AesGcmCipher cipher = new AesGcmCipher(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), null, (short) 1);
        DataSourceService service = new DataSourceService(dataSourceDao, taskDao,
                new DataSourceRegistry(dataSourceDao, cipher), cipher, idGenerator);
        mvc = mockMvc(new DataSourceController(service));
        adminToken = loginAs("ds-admin", Role.ADMIN);
    }

    @Test
    @DisplayName("ADMIN 新建数据源：口令加密落库、响应只回 hasPassword")
    void should_create_When_admin_andNeverEchoPassword() throws Exception {
        mvc.perform(post("/api/datasources")
                        .header(CurrentUser.TOKEN_HEADER, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("ds-1", "mysql", "DEV", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.name").value("ds-1"))
                .andExpect(jsonPath("$.data.hasPassword").value(true))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordCipher").doesNotExist());

        String cipher = jdbc.queryForObject(
                "SELECT password_cipher FROM ds_datasource WHERE name='ds-1'", String.class);
        assertNotNull(cipher, "口令密文必须落库");
        assertFalse(cipher.contains("Sec@12345"), "落库内容不能是明文：" + cipher);
        // 审计：写入一条 DS_CREATE
        assertEquals(1, auditCount(), "写操作必须留审计");
        Map<String, String> audit = lastAudit();
        assertEquals("DS_CREATE", audit.get("action"));
        assertEquals("SUCCESS", audit.get("result"));
        assertFalse(String.valueOf(audit.get("params")).contains("Sec@12345"),
                "审计参数中的口令必须脱敏");
    }

    @Test
    @DisplayName("非 ADMIN（OPERATOR）新建被拒 403 E8001，且不写成功审计")
    void should_reject_When_notAdmin() throws Exception {
        String operator = loginAs("ds-operator", Role.OPERATOR);
        mvc.perform(post("/api/datasources")
                        .header(CurrentUser.TOKEN_HEADER, operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("ds-2", "mysql", "DEV", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("E8001"))
                .andExpect(jsonPath("$.data.required").value("DS_MANAGE"));
        assertEquals(0L, countDataSources(), "被拒请求不能落库");
        Map<String, String> audit = lastAudit();
        assertEquals("DENIED", audit.get("result"), "拒绝也要留痕（架构 §12.2）");
    }

    @Test
    @DisplayName("重名新建被拒 E1001")
    void should_reject_When_duplicateName() throws Exception {
        newDataSource("dup", DbType.MYSQL);
        mvc.perform(post("/api/datasources")
                        .header(CurrentUser.TOKEN_HEADER, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("dup", "mysql", "DEV", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E1001"))
                .andExpect(jsonPath("$.data.field").value("name"));
    }

    @Test
    @DisplayName("缺名称：400 E1001 + fieldErrors.name")
    void should_returnFieldErrors_When_nameMissing() throws Exception {
        mvc.perform(post("/api/datasources")
                        .header(CurrentUser.TOKEN_HEADER, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(null, "mysql", "DEV", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E1001"))
                .andExpect(jsonPath("$.data.fieldErrors.name").exists());
    }

    @Test
    @DisplayName("删除未被引用的数据源成功；被任务引用则 E2003")
    void should_deleteOrReject_When_inUse() throws Exception {
        long free = newDataSource("free", DbType.MYSQL);
        mvc.perform(delete("/api/datasources/" + free)
                        .header(CurrentUser.TOKEN_HEADER, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        long used = newDataSource("used", DbType.MYSQL);
        long target = newDataSource("used-tgt", DbType.MYSQL);
        newTask(used, target);
        mvc.perform(delete("/api/datasources/" + used)
                        .header(CurrentUser.TOKEN_HEADER, adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("E2003"))
                .andExpect(jsonPath("$.data.referencedTasks").value(1));
    }

    @Test
    @DisplayName("编辑：名称变更生效；不存在返回 E2004")
    void should_update_When_exists() throws Exception {
        long id = newDataSource("before", DbType.MYSQL);
        mvc.perform(put("/api/datasources/" + id)
                        .header(CurrentUser.TOKEN_HEADER, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("after", "mysql", "DEV", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("after"));

        mvc.perform(put("/api/datasources/99999999")
                        .header(CurrentUser.TOKEN_HEADER, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("nope", "mysql", "DEV", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("E2004"));
    }

    @Test
    @DisplayName("列表与详情：口令永不出现")
    void should_listAndGet_withoutPassword() throws Exception {
        long id = newDataSource("listed", DbType.POSTGRESQL);
        mvc.perform(get("/api/datasources").header(CurrentUser.TOKEN_HEADER, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].hasPassword").value(true));
        mvc.perform(get("/api/datasources/" + id).header(CurrentUser.TOKEN_HEADER, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dbType").value("POSTGRESQL"))
                .andExpect(jsonPath("$.data.passwordCipher").doesNotExist());
    }

    @Test
    @DisplayName("poolMinIdle > poolMaxSize 被拒 E2005")
    void should_reject_When_poolConfigInvalid() throws Exception {
        Map<String, Object> body = body("bad-pool", "mysql", "DEV", null);
        body.put("poolMaxSize", Integer.valueOf(2));
        body.put("poolMinIdle", Integer.valueOf(5));
        mvc.perform(post("/api/datasources")
                        .header(CurrentUser.TOKEN_HEADER, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(com.dbsync.common.util.JsonUtil.toJson(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("E2005"));
    }

    private long countDataSources() {
        Long n = jdbc.queryForObject("SELECT COUNT(1) FROM ds_datasource", Long.class);
        return n == null ? 0L : n.longValue();
    }

    private static String json(String name, String dbType, String env, String schema) {
        return com.dbsync.common.util.JsonUtil.toJson(body(name, dbType, env, schema));
    }

    private static Map<String, Object> body(String name, String dbType, String env, String schema) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("dbType", dbType);
        map.put("env", env);
        map.put("host", "127.0.0.1");
        map.put("port", Integer.valueOf(3306));
        map.put("database", "demo");
        map.put("schema", schema);
        map.put("username", "root");
        map.put("password", "Sec@12345");
        map.put("identifierCase", "LOWER");
        return map;
    }
}
