package com.dbsync.api.security;

import com.dbsync.common.enums.AuditResult;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.core.port.AuditEntry;
import com.dbsync.core.port.AuditPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * RBAC 拦截器（架构 §12.3：注解 + 拦截器，不用 Spring Security 表达式）。
 *
 * <p>处理顺序：
 * <ol>
 *   <li>解析 {@link CurrentUser#TOKEN_HEADER} → {@link AuthService#authenticate}；</li>
 *   <li>无令牌/过期 → 抛 {@link ErrorCode#TOKEN_INVALID}（HTTP 401）；</li>
 *   <li>读取 {@link RequiresPermission}（方法优先、类兜底）；</li>
 *   <li>角色不含该权限 → 落审计 {@code DENIED} 并抛 {@link ErrorCode#NO_PERMISSION}（403）；</li>
 *   <li>最后把用户绑定到 {@link CurrentUser} 的 ThreadLocal，{@code afterCompletion} 清理。</li>
 * </ol>
 *
 * <p><b>越权必须落审计</b>（PRD SEC-01）：{@code result='DENIED'}，
 * {@code target_name} 记录 {@code HTTP 方法 + 路径}，便于事后追责。
 */
public class RbacInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RbacInterceptor.class);

    /** 拦截器顺序：必须在审计拦截器之前（越权时不再重复写 FAIL 审计）。 */
    public static final int ORDER = 10;

    /**
     * 免令牌路径（登录 + 自描述接口目录）。
     *
     * <p>{@code /api/auth/login} 必须免令牌：它是"用口令换令牌"的入口，
     * 若也要求令牌则永远打不开（该缺陷由 {@code EndToEndSmokeTest} 发现并修复）。
     */
    private static final java.util.Set<String> PUBLIC_PATHS =
            java.util.Set.of("/api/auth/login", "/api/openapi.json");

    private final AuthService authService;
    private final AuditPort auditPort;

    /**
     * 构造。
     *
     * @param authService 认证服务
     * @param auditPort   审计端口（可为 null，表示不落审计，仅测试用）
     */
    public RbacInterceptor(AuthService authService, AuditPort auditPort) {
        this.authService = authService;
        this.auditPort = auditPort;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        HandlerMethod method = (HandlerMethod) handler;
        if (isPublic(request)) {
            // 登录接口必须免令牌，否则"用令牌换令牌"永远打不开（冒烟测试发现的真实缺陷）
            return true;
        }
        Optional<CurrentUser> found = authService.authenticate(
                request.getHeader(CurrentUser.TOKEN_HEADER));
        if (found.isEmpty()) {
            throw new DbSyncException(ErrorCode.TOKEN_INVALID, request.getRequestURI());
        }
        CurrentUser user = found.get();
        CurrentUser.set(user);
        Permission required = resolvePermission(method);
        if (!user.can(required)) {
            recordDenied(user, request, required);
            throw new DbSyncException(ErrorCode.NO_PERMISSION, required.code())
                    .with("required", required.code())
                    .with("role", user.getRole().code());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        CurrentUser.clear();
    }

    /**
     * 是否免令牌路径。
     *
     * <p>只有两类：登录（拿令牌）与接口目录（自描述文档，不含任何数据）。
     * 其它路径一律要求令牌，宁可多一次鉴权，也不留"忘记加注解就裸奔"的口子。
     *
     * @param request 请求
     * @return true 表示免鉴权
     */
    static boolean isPublic(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        String contextPath = request.getContextPath();
        String path = contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length()) : uri;
        return PUBLIC_PATHS.contains(path);
    }

    /**
     * 解析所需权限：方法级优先，类级兜底。
     *
     * @param method 处理器方法
     * @return 权限点；未声明返回 null
     */
    static Permission resolvePermission(HandlerMethod method) {        RequiresPermission onMethod = method.getMethodAnnotation(RequiresPermission.class);
        if (onMethod != null) {
            return onMethod.value();
        }
        RequiresPermission onClass = method.getBeanType().getAnnotation(RequiresPermission.class);
        return onClass == null ? null : onClass.value();
    }

    private void recordDenied(CurrentUser user, HttpServletRequest request, Permission required) {
        if (auditPort == null) {
            return;
        }
        try {
            AuditEntry entry = new AuditEntry(user.getUsername(), user.getRole().code(),
                    required == null ? "UNKNOWN" : required.code(), "HTTP",
                    null, request.getMethod() + " " + request.getRequestURI(), null,
                    AuditResult.DENIED, ErrorCode.NO_PERMISSION.code(),
                    request.getRemoteAddr(), request.getHeader("User-Agent"), traceId(request),
                    Instant.now(), null);
            auditPort.record(entry);
        } catch (RuntimeException e) {
            // 审计实现本身已吞异常；这里再兜一层，保证越权响应不被审计故障改写
            log.warn("越权审计失败 uri={} err={}", request.getRequestURI(), e.toString());
        }
    }

    private static String traceId(HttpServletRequest request) {
        String trace = request.getHeader("X-Trace-Id");
        return trace == null || trace.isBlank() ? null : trace;
    }
}
