package com.dbsync.server.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * traceId 过滤器（架构 §11：请求链路追踪的最小实现）。
 *
 * <p>// ADDED: 04 文档要求在日志里带 traceId，但没有列具体的 Filter 类。
 * 规则：
 * <ol>
 *   <li>入站若带 {@code X-Trace-Id} 则沿用（网关已生成时保持全链路一致）；</li>
 *   <li>否则生成 16 位十六进制；</li>
 *   <li>写入 MDC 并回写响应头，<b>finally 里必须清理</b>——线程池复用下不清理会串号。</li>
 * </ol>
 */
public class TraceIdFilter implements Filter {

    /** 追踪头名。 */
    public static final String HEADER = "X-Trace-Id";
    /** MDC 键名。 */
    public static final String MDC_KEY = "traceId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String traceId = null;
        if (request instanceof HttpServletRequest) {
            traceId = ((HttpServletRequest) request).getHeader(HEADER);
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(MDC_KEY, traceId);
        try {
            if (response instanceof HttpServletResponse) {
                ((HttpServletResponse) response).setHeader(HEADER, traceId);
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
