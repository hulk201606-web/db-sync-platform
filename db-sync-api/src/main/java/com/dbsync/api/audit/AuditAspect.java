package com.dbsync.api.audit;

import com.dbsync.api.security.CurrentUser;
import com.dbsync.common.enums.AuditResult;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.core.port.AuditEntry;
import com.dbsync.core.port.AuditPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 审计拦截器：拦截 {@link Audited}，落 {@code sys_audit_log}。
 *
 * <p><b>为什么不是 AspectJ</b>：本项目的依赖白名单（04 文档 §1.1）不含
 * {@code spring-boot-starter-aop}，因此不能用 {@code @Aspect} 织入。
 * 改成 {@link HandlerInterceptor} 实现同样的语义：
 * <ul>
 *   <li>{@code preHandle} 记录开始时间与请求参数；</li>
 *   <li>{@code afterCompletion} 根据异常与否写 {@code SUCCESS}/{@code FAIL}。</li>
 * </ul>
 * {@code // ADDED: 白名单禁止 AOP 依赖，@Aspect 改为 HandlerInterceptor 等价实现}。
 *
 * <p><b>不重复写 DENIED</b>：顺序上本拦截器排在 {@code RbacInterceptor} 之后
 * （{@link #ORDER}），越权请求在 Rbac 阶段就抛异常且 {@code preHandle} 未成功，
 * Spring 不会回调本拦截器的 {@code afterCompletion}，因此 DENIED 只由 Rbac 写一次。
 *
 * <p><b>只能拿到 Query/String 参数</b>：请求体（JSON）在拦截器阶段已被读取，
 * 需要精确记录请求体的动作（如 skip-gate 的 4 项实测值）由 Service 显式调用
 * {@link AuditPort#record} 补一条更详细的审计。
 */
public class AuditAspect implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    /** 拦截器顺序：必须在 RBAC 之后。 */
    public static final int ORDER = 20;

    private static final String ATTR_START = AuditAspect.class.getName() + ".start";
    private static final String ATTR_ACTION = AuditAspect.class.getName() + ".action";
    private static final String ATTR_TARGET = AuditAspect.class.getName() + ".target";

    private final AuditPort auditPort;

    /**
     * 构造。
     *
     * @param auditPort 审计端口
     */
    public AuditAspect(AuditPort auditPort) {
        this.auditPort = auditPort;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        Audited audited = resolve((HandlerMethod) handler);
        if (audited == null) {
            return true;
        }
        request.setAttribute(ATTR_START, Long.valueOf(System.nanoTime()));
        request.setAttribute(ATTR_ACTION, audited.action());
        request.setAttribute(ATTR_TARGET, audited.targetType());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        Object action = request.getAttribute(ATTR_ACTION);
        if (action == null) {
            return;
        }
        Object target = request.getAttribute(ATTR_TARGET);
        Object start = request.getAttribute(ATTR_START);
        long durationMs = start instanceof Long
                ? (System.nanoTime() - ((Long) start).longValue()) / 1_000_000L : 0L;
        CurrentUser user = CurrentUser.get();
        String actor = user == null ? "anonymous" : user.getUsername();
        String role = user == null ? "SYSTEM" : user.getRole().code();
        AuditResult result = ex == null ? AuditResult.SUCCESS : AuditResult.FAILED;
        String errorCode = null;
        if (ex instanceof DbSyncException) {
            errorCode = ((DbSyncException) ex).errorCode().code();
        } else if (ex != null) {
            errorCode = ErrorCode.INTERNAL_ERROR.code();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("method", request.getMethod());
        params.put("path", request.getRequestURI());
        params.put("status", Integer.valueOf(response.getStatus()));
        params.put("durationMs", Long.valueOf(durationMs));
        Map<String, String[]> raw = request.getParameterMap();
        if (raw != null && !raw.isEmpty()) {
            for (Map.Entry<String, String[]> entry : raw.entrySet()) {
                String[] values = entry.getValue();
                params.put(entry.getKey(), values == null || values.length == 0
                        ? null : String.join(",", values));
            }
        }
        try {
            auditPort.record(new AuditEntry(actor, role, String.valueOf(action),
                    target == null ? null : String.valueOf(target), null,
                    request.getRequestURI(), AuditService.digest(params), result, errorCode,
                    request.getRemoteAddr(), request.getHeader("User-Agent"),
                    request.getHeader("X-Trace-Id"), Instant.now(), null));
        } catch (RuntimeException e) {
            log.warn("自动审计失败（已忽略） action={} err={}", action, e.toString());
        }
    }

    /**
     * 解析注解：方法优先，类兜底。
     *
     * @param method 处理器方法
     * @return 注解；无则 null
     */
    static Audited resolve(HandlerMethod method) {
        Audited onMethod = method.getMethodAnnotation(Audited.class);
        if (onMethod != null) {
            return onMethod;
        }
        return method.getBeanType().getAnnotation(Audited.class);
    }
}
