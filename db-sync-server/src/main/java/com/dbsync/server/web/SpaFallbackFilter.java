package com.dbsync.server.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPA 路由回落（配合前端 React Router 的 BrowserRouter）。
 *
 * <p>// ADDED: 前端控制台用 BrowserRouter，直接在地址栏刷新 {@code /tasks/123}
 * 会打到后端，而后端没有这个路径 → 404 白屏。本过滤器把"看起来像前端路由"的
 * GET 请求 forward 到 {@code /index.html}，由前端路由自己渲染。
 *
 * <p><b>严格不碰的范围</b>（避免把接口 404 变成 200 的 HTML，掩盖真实错误）：
 * <ul>
 *   <li>{@code /api/**}、{@code /actuator/**}：接口与运维端点，404 就该是 404；</li>
 *   <li>带扩展名的路径（{@code .js/.css/.png/.json...}）：静态资源，交回容器处理；</li>
 *   <li>非 GET/HEAD 请求。</li>
 * </ul>
 */
public class SpaFallbackFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(SpaFallbackFilter.class);

    /** 前端单页入口。 */
    private static final String SPA_ENTRY = "/index.html";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse)) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        if (!shouldForward(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }
        log.debug("SPA 路由回落 uri={} -> {}", httpRequest.getRequestURI(), SPA_ENTRY);
        RequestDispatcher dispatcher = httpRequest.getRequestDispatcher(SPA_ENTRY);
        dispatcher.forward(request, response);
    }

    /**
     * 是否需要回落到单页入口。
     *
     * @param request 请求
     * @return true 表示 forward 到 index.html
     */
    static boolean shouldForward(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            return false;
        }
        String uri = request.getRequestURI();
        if (uri == null || uri.isEmpty()) {
            return false;
        }
        String contextPath = request.getContextPath();
        String path = contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length()) : uri;
        if (path.isEmpty() || "/".equals(path) || SPA_ENTRY.equals(path)) {
            // 根路径与入口本身交给容器（欢迎页 / 静态资源）
            return false;
        }
        if (path.startsWith("/api/") || "/api".equals(path)
                || path.startsWith("/actuator/") || "/actuator".equals(path)) {
            return false;
        }
        if (path.startsWith("/h2-console")) {
            return false;
        }
        // 带扩展名的一律视为静态资源
        String lastSegment = path.substring(path.lastIndexOf('/') + 1);
        return lastSegment.indexOf('.') < 0;
    }
}
