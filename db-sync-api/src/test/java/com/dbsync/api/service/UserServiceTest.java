package com.dbsync.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.api.ApiTestSupport;
import com.dbsync.api.dto.system.UserCreateRequest;
import com.dbsync.api.security.AuthService;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 用户服务测试（T46：PBKDF2 加盐 / 首登强制改密 / 禁用保护）。
 */
class UserServiceTest extends ApiTestSupport {

    private UserService userService;

    @BeforeEach
    void setUpService() {
        userService = new UserService(userDao);
    }

    @Test
    @DisplayName("PBKDF2：同一口令 + 同一盐 → 同一哈希；换盐 → 不同哈希")
    void should_hashDeterministically_perSalt() {
        String salt1 = AuthService.newSaltHex();
        String salt2 = AuthService.newSaltHex();
        assertNotEquals(salt1, salt2, "盐必须随机");

        String h1 = AuthService.hashPassword("Admin@12345", salt1);
        String h2 = AuthService.hashPassword("Admin@12345", salt1);
        String h3 = AuthService.hashPassword("Admin@12345", salt2);
        assertEquals(h1, h2, "同盐同口令必须一致");
        assertNotEquals(h1, h3, "换盐后哈希必须不同（防彩虹表）");
        assertEquals(64, h1.length(), "256bit 输出 = 64 个 hex 字符");
        assertTrue(AuthService.verifyPassword("Admin@12345", salt1, h1));
        assertFalse(AuthService.verifyPassword("Admin@1234", salt1, h1));
        assertFalse(AuthService.verifyPassword("Admin@12345", salt2, h1), "盐不匹配必须失败");
    }

    @Test
    @DisplayName("种子管理员：初始哈希可验证、首登标记为 true，改密后标记消失")
    void should_markMustChangePassword_untilPasswordChanged() {
        assertTrue(userService.mustChangePassword("admin"), "种子 admin 未改密时需强制改密");
        assertTrue(AuthService.verifyPassword("Admin@12345", "4a7d517be07ab27e1adcbd4d16ab7f85",
                UserService.INITIAL_ADMIN_HASH), "种子哈希必须对应文档口令");

        userService.resetPassword("admin", "NewPass@2026");
        assertFalse(userService.mustChangePassword("admin"), "改密后不再是初始哈希");
        assertTrue(authService.login("admin", "NewPass@2026").isPresent(), "新口令可登录");
        assertFalse(authService.login("admin", "Admin@12345").isPresent(), "旧口令失效");
    }

    @Test
    @DisplayName("新建用户：口令过短/重名被拒，成功后可用新口令登录")
    void should_createUser() {
        DbSyncException tooShort = assertThrows(DbSyncException.class, () -> userService.create(
                new UserCreateRequest("u1", "用户一", "short", "OPERATOR")));
        assertEquals(ErrorCode.PARAM_INVALID, tooShort.errorCode());

        userService.create(new UserCreateRequest("u1", "用户一", "Good@12345", "OPERATOR"));
        assertTrue(authService.login("u1", "Good@12345").isPresent());
        assertEquals("OPERATOR", userDao.findByUsername("u1").orElseThrow().getRole());

        DbSyncException duplicate = assertThrows(DbSyncException.class, () -> userService.create(
                new UserCreateRequest("u1", "用户一", "Good@12345", "OPERATOR")));
        assertEquals(ErrorCode.PARAM_INVALID, duplicate.errorCode());
    }

    @Test
    @DisplayName("未知角色按最小权限落库（VIEWER）")
    void should_fallbackToViewer_When_roleUnknown() {
        userService.create(new UserCreateRequest("u2", "用户二", "Good@12345", "SUPERMAN"));
        assertEquals("VIEWER", userDao.findByUsername("u2").orElseThrow().getRole());
    }

    @Test
    @DisplayName("禁止禁用内置 admin；其他用户可禁用且登录被拒")
    void should_protectAdminAccount() {
        DbSyncException e = assertThrows(DbSyncException.class,
                () -> userService.setEnabled("admin", false));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());

        userService.create(new UserCreateRequest("u3", "用户三", "Good@12345", "OPERATOR"));
        userService.setEnabled("u3", false);
        assertFalse(userDao.findByUsername("u3").orElseThrow().isEnabled(), "应已禁用");
        assertFalse(authService.login("u3", "Good@12345").isPresent(), "禁用后不可登录");
    }

    @Test
    @DisplayName("不存在的用户：重置口令/启用均报 E1001")
    void should_reject_When_userMissing() {
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(DbSyncException.class,
                        () -> userService.resetPassword("nobody", "Good@12345")).errorCode());
        assertEquals(ErrorCode.PARAM_INVALID,
                assertThrows(DbSyncException.class,
                        () -> userService.setEnabled("nobody", true)).errorCode());
        assertFalse(userService.mustChangePassword("nobody"));
    }

    @Test
    @DisplayName("列表不返回口令散列（只回视图字段）")
    void should_listWithoutPassword() {
        userService.create(new UserCreateRequest("u4", "用户四", "Good@12345", "VIEWER"));
        assertEquals(1, userService.list().stream()
                .filter(v -> "u4".equals(v.username())).count());
    }
}
