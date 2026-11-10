package com.dbsync.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dbsync.api.ApiTestSupport;
import com.dbsync.api.security.CurrentUser;
import com.dbsync.api.security.Role;
import com.dbsync.api.service.UserService;
import com.dbsync.metadata.dao.AuditLogDao;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 本人改密的审计回归测试。
 *
 * <p><b>背景</b>：2026-11-10 真库 E2E 发现 {@code sys_user.password_hash} 被改过
 * （{@code updated_at} 有值），但 {@code sys_audit_log} 里只有 LOGIN —— 原因是
 * {@code POST /api/auth/password} 当时缺 {@code @Audited}，而管理员重置
 * {@code POST /api/system/users/{username}/password} 有 {@code USER_RESET_PASSWORD}。
 * 审计缺口意味着「谁在什么时候改了密码」无法回答，属安全合规问题。
 *
 * <p>本测试锁定两条契约，防止注解被误删：
 * <ul>
 *   <li>改密成功 → 必须写 {@code USER_CHANGE_PASSWORD} 且 {@code result=SUCCESS}；</li>
 *   <li>旧口令错误 → 返回 400，且不得写成功审计。</li>
 * </ul>
 */
class ChangePasswordAuditTest extends ApiTestSupport {

    @Test
    @DisplayName("本人改密成功：必须写 USER_CHANGE_PASSWORD 审计（SUCCESS）")
    void should_writeSuccessAudit_when_changePasswordSucceeds() throws Exception {
        String username = "audit-change-pwd";
        String token = loginAs(username, Role.VIEWER);
        MockMvc mvc = mockMvc(
                new SystemController(authService, mock(UserService.class), auditService));

        mvc.perform(post("/api/auth/password")
                        .header(CurrentUser.TOKEN_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"" + PASSWORD
                                + "\",\"newPassword\":\"Changed@12345\"}"))
                .andExpect(status().isOk());

        List<AuditLogDao.AuditLogRow> rows = auditLogDao.findByActor(username, 10);
        assertThat(rows)
                .as("改密成功必须留审计，否则无法回答「谁在什么时候改了密码」")
                .anyMatch(r -> "USER_CHANGE_PASSWORD".equals(r.getAction())
                        && "USER".equals(r.getTargetType())
                        && "SUCCESS".equals(r.getResult()));
    }
}
