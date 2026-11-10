package com.dbsync.api.controller;

import com.dbsync.api.audit.Audited;
import com.dbsync.api.audit.AuditService;
import com.dbsync.api.dto.common.ApiResponse;
import com.dbsync.api.dto.system.ChangePasswordRequest;
import com.dbsync.api.dto.system.LoginRequest;
import com.dbsync.api.dto.system.UserCreateRequest;
import com.dbsync.api.dto.system.UserView;
import com.dbsync.api.security.AuthService;
import com.dbsync.api.security.CurrentUser;
import com.dbsync.api.security.Permission;
import com.dbsync.api.security.RequiresPermission;
import com.dbsync.api.service.UserService;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.metadata.dao.AuditLogDao;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统 API（T46：登录 / 改密 / 用户管理 / 审计查询与导出）。
 *
 * <p>拆成两组路径：
 * <ul>
 *   <li>{@code /api/auth/**}：登录、登出、改密（登录接口<b>不需要</b>令牌）；</li>
 *   <li>{@code /api/system/**}：用户与审计（需要令牌；用户管理仅 ADMIN）。</li>
 * </ul>
 */
@RestController
public class SystemController {

    private final AuthService authService;
    private final UserService userService;
    private final AuditService auditService;

    /**
     * 构造。
     *
     * @param authService  认证服务
     * @param userService  用户服务
     * @param auditService 审计服务
     */
    public SystemController(AuthService authService, UserService userService,
                            AuditService auditService) {
        this.authService = authService;
        this.userService = userService;
        this.auditService = auditService;
    }

    /**
     * 登录（无需令牌）。
     *
     * @param req 请求
     * @return 令牌与用户信息（含 {@code mustChangePassword}）
     */
    @PostMapping("/api/auth/login")
    public ApiResponse login(@RequestBody(required = false) LoginRequest req) {
        if (req == null) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "请求体不能为空");
        }
        Optional<CurrentUser> user = authService.login(req.username(), req.password());
        if (user.isEmpty()) {
            auditService.record(new com.dbsync.core.port.AuditEntry(
                    req.username() == null ? "anonymous" : req.username(), "UNKNOWN", "LOGIN",
                    "USER", null, req.username(),
                    AuditService.digest(Map.of("username",
                            req.username() == null ? "" : req.username())),
                    com.dbsync.common.enums.AuditResult.FAILED, ErrorCode.TOKEN_INVALID.code(),
                    null, null, null, Instant.now(), null));
            throw new DbSyncException(ErrorCode.TOKEN_INVALID, "用户名或口令错误");
        }
        CurrentUser current = user.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", current.getToken());
        data.put("username", current.getUsername());
        data.put("displayName", current.getDisplayName());
        data.put("role", current.getRole().code());
        data.put("permissions", current.getRole().permissions());
        data.put("expiresAt", current.getExpiresAt().toString());
        data.put("mustChangePassword", Boolean.valueOf(
                userService.mustChangePassword(current.getUsername())));
        return ApiResponse.ok(data);
    }

    /**
     * 登出。
     *
     * @return 空响应
     */
    @PostMapping("/api/auth/logout")
    public ApiResponse logout() {
        CurrentUser user = CurrentUser.get();
        if (user != null) {
            authService.logout(user.getToken());
        }
        return ApiResponse.ok();
    }

    /**
     * 本人改密。
     *
     * @param req 请求
     * @return 空响应
     */
    @PostMapping("/api/auth/password")
    public ApiResponse changePassword(@RequestBody(required = false) ChangePasswordRequest req) {
        CurrentUser user = CurrentUser.require();
        if (req == null || req.newPassword() == null) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "新口令不能为空");
        }
        boolean ok = authService.changePassword(user.getUsername(), req.oldPassword(),
                req.newPassword());
        if (!ok) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "旧口令错误");
        }
        return ApiResponse.ok();
    }

    /**
     * 用户列表。
     *
     * @return 用户列表
     */
    @GetMapping("/api/system/users")
    @RequiresPermission(Permission.USER_MANAGE)
    public ApiResponse users() {
        List<Object> data = new ArrayList<>();
        for (UserView view : userService.list()) {
            data.add(view.toMap());
        }
        return ApiResponse.ok(data);
    }

    /**
     * 新建用户。
     *
     * @param req 请求
     * @return 用户
     */
    @PostMapping("/api/system/users")
    @RequiresPermission(Permission.USER_MANAGE)
    @Audited(action = "USER_CREATE", targetType = "USER")
    public ApiResponse createUser(@RequestBody(required = false) UserCreateRequest req) {
        return ApiResponse.ok(userService.create(req).toMap());
    }

    /**
     * 启用/禁用。
     *
     * @param username 用户名
     * @param enabled  是否启用
     * @return 空响应
     */
    @PutMapping("/api/system/users/{username}/enabled")
    @RequiresPermission(Permission.USER_MANAGE)
    @Audited(action = "USER_ENABLE", targetType = "USER")
    public ApiResponse setEnabled(@PathVariable("username") String username,
                                  @RequestParam("enabled") boolean enabled) {
        userService.setEnabled(username, enabled);
        return ApiResponse.ok();
    }

    /**
     * 管理员重置口令。
     *
     * @param username 用户名
     * @param req      请求（使用 newPassword）
     * @return 空响应
     */
    @PostMapping("/api/system/users/{username}/password")
    @RequiresPermission(Permission.USER_MANAGE)
    @Audited(action = "USER_RESET_PASSWORD", targetType = "USER")
    public ApiResponse resetPassword(@PathVariable("username") String username,
                                     @RequestBody(required = false) ChangePasswordRequest req) {
        userService.resetPassword(username, req == null ? null : req.newPassword());
        return ApiResponse.ok();
    }

    /**
     * 审计查询（四维筛选）。
     *
     * @param from       起始时间（ISO-8601，可空）
     * @param to         结束时间（可空）
     * @param actor      操作人（可空）
     * @param targetType 目标类型（可空）
     * @param targetId   目标 ID（可空）
     * @param action     动作（可空）
     * @param result     结果（可空）
     * @param limit      上限
     * @return 审计列表
     */
    @GetMapping("/api/system/audit")
    @RequiresPermission(Permission.TASK_VIEW)
    public ApiResponse audit(@RequestParam(value = "from", required = false) String from,
                             @RequestParam(value = "to", required = false) String to,
                             @RequestParam(value = "actor", required = false) String actor,
                             @RequestParam(value = "targetType", required = false) String targetType,
                             @RequestParam(value = "targetId", required = false) Long targetId,
                             @RequestParam(value = "action", required = false) String action,
                             @RequestParam(value = "result", required = false) String result,
                             @RequestParam(value = "limit", defaultValue = "500") int limit) {
        List<AuditLogDao.AuditLogRow> rows = auditService.search(parseInstant(from),
                parseInstant(to), actor, targetType, targetId, action, result, limit);
        return ApiResponse.ok(AuditService.toViews(rows));
    }


    /**
     * 审计导出 CSV。
     *
     * @param from  起始时间
     * @param to    结束时间
     * @param actor 操作人
     * @return CSV 响应
     */
    @GetMapping("/api/system/audit/export")    @RequiresPermission(Permission.USER_MANAGE)
    public ResponseEntity<String> exportAudit(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "actor", required = false) String actor) {
        List<AuditLogDao.AuditLogRow> rows = auditService.search(parseInstant(from),
                parseInstant(to), actor, null, null, null, null, 5000);
        String csv = auditService.exportCsv(rows);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (RuntimeException e) {
            throw new DbSyncException(ErrorCode.PARAM_INVALID, "时间格式需为 ISO-8601：" + value);
        }
    }
}
