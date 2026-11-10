package com.dbsync.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dbsync.api.ApiTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RBAC 测试（T42 验收 1/2）：3 角色 × 12 权限 = 36 组合，与架构 §12.3 矩阵逐格对齐。
 *
 * <p>矩阵（§12.3）到权限点的映射见 {@link Role} 的类注释；
 * 这里用"期望表 + 参数化"把文档表格<b>原样</b>搬到代码里，
 * 任何人改动 {@link Role} 的权限集合都会立刻被这 36 个用例挡住。
 */
class RbacTest extends ApiTestSupport {

    /** 期望矩阵：角色 → 权限集合。直接照抄架构 §12.3。 */
    private static final Map<Role, List<Permission>> MATRIX = Map.of(
            Role.VIEWER, List.of(Permission.TASK_VIEW, Permission.CUTOVER_VIEW),
            Role.OPERATOR, List.of(Permission.TASK_VIEW, Permission.CUTOVER_VIEW,
                    Permission.TASK_EDIT, Permission.TASK_EXEC, Permission.COMPARE_RUN,
                    Permission.DEAD_LETTER_MANAGE),
            Role.ADMIN, List.of(Permission.values()));

    static Stream<Arguments> matrixCases() {
        List<Arguments> cases = new ArrayList<>();
        for (Role role : Role.values()) {
            for (Permission permission : Permission.values()) {
                boolean expected = MATRIX.get(role).contains(permission);
                cases.add(Arguments.of(role, permission, Boolean.valueOf(expected)));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} × {1} → {2}")
    @MethodSource("matrixCases")
    @DisplayName("36 组合与架构 §12.3 权限矩阵一致")
    void should_matchArchMatrix(Role role, Permission permission, boolean expected) {
        assertEquals(expected, role.has(permission),
                role + " 对 " + permission + " 的判定与 §12.3 不一致");
    }

    @Test
    @DisplayName("ADMIN 命中受保护端点")
    void should_allow_When_admin() throws Exception {
        String token = loginAs("admin1", Role.ADMIN);
        mockMvc(new ProbeController()).perform(get("/probe/skip-gate")
                        .header(CurrentUser.TOKEN_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
    }

    @Test
    @DisplayName("越权返回 403 + E8001 且落 DENIED 审计")
    void should_reject403AndAudit_When_viewerSkipsGate() throws Exception {
        String token = loginAs("viewer1", Role.VIEWER);
        long before = auditCount();
        mockMvc(new ProbeController()).perform(get("/probe/skip-gate")
                        .header(CurrentUser.TOKEN_HEADER, token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("E8001"))
                .andExpect(jsonPath("$.data.required").value("SKIP_GATE"));
        assertEquals(before + 1L, auditCount(), "越权必须落一条审计");
        Map<String, String> audit = lastAudit();
        assertEquals("DENIED", audit.get("result"));
        assertEquals("SKIP_GATE", audit.get("action"));
    }

    @Test
    @DisplayName("OPERATOR 可查只读端点但不可切流")
    void should_allowReadOnly_When_operator() throws Exception {
        String token = loginAs("operator1", Role.OPERATOR);
        mockMvc(new ProbeController()).perform(get("/probe/read")
                        .header(CurrentUser.TOKEN_HEADER, token))
                .andExpect(status().isOk());
        mockMvc(new ProbeController()).perform(get("/probe/skip-gate")
                        .header(CurrentUser.TOKEN_HEADER, token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("缺少令牌返回 401 + E8004")
    void should_reject401_When_noToken() throws Exception {
        mockMvc(new ProbeController()).perform(get("/probe/read"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("E8004"));
    }

    @Test
    @DisplayName("未知角色降级为 VIEWER（最小权限）")
    void should_fallbackToViewer_When_roleUnknown() {
        assertEquals(Role.VIEWER, Role.parse("superuser"));
        assertEquals(Role.VIEWER, Role.parse(null));
        assertEquals(Role.OPERATOR, Role.parse(" operator "));
        assertFalse(Role.VIEWER.has(Permission.SKIP_GATE));
        assertTrue(Role.ADMIN.has(Permission.USER_MANAGE));
    }

    /** 探针控制器：两个端点覆盖"只读"与"ADMIN only"。 */
    @RestController
    static class ProbeController {

        /**
         * 只读端点。
         *
         * @return 统一响应
         */
        @GetMapping("/probe/read")
        @RequiresPermission(Permission.TASK_VIEW)
        public com.dbsync.api.dto.common.ApiResponse read() {
            return com.dbsync.api.dto.common.ApiResponse.ok();
        }

        /**
         * 跳卡点端点（ADMIN only）。
         *
         * @return 统一响应
         */
        @GetMapping("/probe/skip-gate")
        @RequiresPermission(Permission.SKIP_GATE)
        public com.dbsync.api.dto.common.ApiResponse skipGate() {
            return com.dbsync.api.dto.common.ApiResponse.ok();
        }
    }
}
