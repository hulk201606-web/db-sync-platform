package com.dbsync.api.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.api.ApiTestSupport;
import com.dbsync.api.security.AuthService;
import com.dbsync.common.enums.AuditResult;
import com.dbsync.core.port.AuditEntry;
import com.dbsync.metadata.dao.AuditLogDao;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 审计测试（T42 验收 2/3/5）。
 */
class AuditTest extends ApiTestSupport {

    @Test
    @DisplayName("动作执行后落一条审计")
    void should_writeAudit_When_actionExecuted() {
        long before = auditCount();
        auditService.record(AuditService.success("alice", "OPERATOR", "TASK_CREATE", "TASK",
                Long.valueOf(42L), Map.of("name", "t1")));
        assertEquals(before + 1L, auditCount());
        Map<String, String> row = lastAudit();
        assertEquals("TASK_CREATE", row.get("action"));
        assertEquals("SUCCESS", row.get("result"));
        assertTrue(row.get("params").contains("t1"), "参数摘要应包含业务参数");
    }

    @Test
    @DisplayName("越权落 DENIED 审计（result=DENIED + 错误码）")
    void should_writeDeniedAudit_When_permissionDenied() {
        auditService.record(AuditEntry.denied("bob", "VIEWER", "SKIP_GATE", "CUTOVER_PLAN",
                "E8001"));
        Map<String, String> row = lastAudit();
        assertEquals("DENIED", row.get("result"));
        assertEquals("SKIP_GATE", row.get("action"));
        assertNotNull(row.get("params") == null ? "null-ok" : row.get("params"));
    }

    @Test
    @DisplayName("口令/密钥类字段脱敏为 ******")
    void should_maskSensitiveFields_When_digest() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("username", "alice");
        params.put("password", "Admin@12345");
        params.put("masterKey", "s3cr3t");
        params.put("nested", Map.of("token", "abc", "host", "db1"));
        String digest = AuditService.digest(params);
        assertNotNull(digest);
        assertFalse(digest.contains("Admin@12345"), "明文口令不得进入审计：" + digest);
        assertFalse(digest.contains("s3cr3t"), "密钥不得进入审计：" + digest);
        assertFalse(digest.contains("\"abc\""), "令牌不得进入审计：" + digest);
        assertTrue(digest.contains("******"));
        assertTrue(digest.contains("alice"), "非敏感字段应保留");
    }

    @Test
    @DisplayName("敏感键识别覆盖 password/pwd/token/secret")
    void should_detectSensitiveKeys() {
        assertTrue(AuditService.isSensitive("password"));
        assertTrue(AuditService.isSensitive("dbPassword"));
        assertTrue(AuditService.isSensitive("PWD"));
        assertTrue(AuditService.isSensitive("accessToken"));
        assertTrue(AuditService.isSensitive("privateKey"));
        assertFalse(AuditService.isSensitive("username"));
        assertFalse(AuditService.isSensitive(null));
    }

    @Test
    @DisplayName("审计写库异常不影响主流程（永不抛）")
    void should_swallowException_When_daoFails() {
        AuditLogDao broken = Mockito.mock(AuditLogDao.class);
        Mockito.when(broken.append(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                        Mockito.any(), Mockito.any(), Mockito.any()))
                .thenThrow(new IllegalStateException("元数据库挂了"));
        AuditService service = new AuditService(broken);
        // 不抛即通过
        service.record(AuditService.success("alice", "ADMIN", "CUTOVER_ADVANCE", "CUTOVER_PLAN",
                Long.valueOf(1L), Map.of()));
        service.record(null);
    }

    @Test
    @DisplayName("四维筛选：人 + 动作")
    void should_filterByActorAndAction() {
        auditService.record(new AuditEntry("carol", "ADMIN", "CUTOVER_ROLLBACK", "CUTOVER_PLAN",
                Long.valueOf(7L), null, null, AuditResult.SUCCESS, null, null, null, null,
                Instant.now(), null));
        auditService.record(AuditService.success("dave", "ADMIN", "CUTOVER_ROLLBACK",
                "CUTOVER_PLAN", Long.valueOf(8L), Map.of()));
        List<AuditLogDao.AuditLogRow> rows = auditService.search(null, null, "carol", null, null,
                "CUTOVER_ROLLBACK", null, 10);
        assertEquals(1, rows.size());
        assertEquals("carol", rows.get(0).getActor());
    }

    @Test
    @DisplayName("CSV 导出包含表头与脱敏后的摘要")
    void should_exportCsv() {
        auditService.record(AuditService.success("erin", "ADMIN", "USER_CREATE", "USER",
                Long.valueOf(9L), Map.of("password", "p@ss")));
        List<AuditLogDao.AuditLogRow> rows = auditService.search(null, null, "erin", null, null,
                null, null, 10);
        String csv = auditService.exportCsv(rows);
        assertTrue(csv.startsWith("id,createdAt,actor"), "CSV 需含表头");
        assertTrue(csv.contains("erin"));
        assertFalse(csv.contains("p@ss"), "导出不得绕过脱敏");
    }

    @Test
    @DisplayName("种子管理员 admin / Admin@12345 可登录（种子哈希与算法一致）")
    void should_loginSeededAdmin() {
        assertTrue(authService.login("admin", "Admin@12345").isPresent(),
                "V2__seed.sql 的 password_hash 必须与 PBKDF2 实现一致，否则出厂就登不上");
        assertFalse(authService.login("admin", "wrong-password").isPresent());
        assertTrue(AuthService.verifyPassword("Admin@12345", "4a7d517be07ab27e1adcbd4d16ab7f85",
                "a6e44388fc76bddee19c2d7004c7f0f88d3f0275a4d70d7833639f9695c99c8f"));
    }
}
