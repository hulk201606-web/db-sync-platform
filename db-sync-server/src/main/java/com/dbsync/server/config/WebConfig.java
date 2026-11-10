package com.dbsync.server.config;

import com.dbsync.api.audit.AuditAspect;
import com.dbsync.api.security.RbacInterceptor;
import com.dbsync.server.web.SpaFallbackFilter;
import com.dbsync.server.web.TraceIdFilter;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层装配：拦截器顺序 + 静态资源 + CORS。
 *
 * <p><b>拦截器顺序是安全约束</b>（T42 验收 1）：先 RBAC 再审计。
 * 反过来的话，未授权请求会被"审计放行"而拿不到 403，或者被拒后没有留痕。
 * 这里用显式 order 而不是注册顺序，避免以后有人调换 {@code addInterceptors} 参数把语义改掉。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RbacInterceptor rbacInterceptor;
    private final AuditAspect auditAspect;

    /**
     * CORS 允许的源（逗号分隔，支持 Ant 风格 {@code *} 通配）。
     * 默认值与 application.yml 的 {@code dbsync.cors.allowed-origins} 一致；
     * 同时用 Java 字段初始化兜底，保证被直接 {@code new WebConfig(...)}（如单测）时也不为 null。
     */
    @Value("${dbsync.cors.allowed-origins:http://localhost:*,http://127.0.0.1:*}")
    private String allowedOrigins = "http://localhost:*,http://127.0.0.1:*";

    /**
     * 构造。
     *
     * @param rbacInterceptor RBAC 拦截器
     * @param auditAspect     审计拦截器
     */
    public WebConfig(RbacInterceptor rbacInterceptor, AuditAspect auditAspect) {
        this.rbacInterceptor = rbacInterceptor;
        this.auditAspect = auditAspect;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // RBAC 先于审计：未授权请求也要留痕（DENIED 由 RBAC 自己写）
        registry.addInterceptor(rbacInterceptor)
                .addPathPatterns("/api/**")
                .order(RbacInterceptor.ORDER);
        registry.addInterceptor(auditAspect)
                .addPathPatterns("/api/**")
                .order(AuditAspect.ORDER);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 大盘页与首页都走 Spring Boot 默认 static 目录，这里只显式声明缓存策略
        registry.addResourceHandler("/dashboard.html", "/index.html")
                .addResourceLocations("classpath:/static/")
                .setCachePeriod(0);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 从配置读取，不再把"允许的源"写死在代码里；默认仍是本机两个调试源
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        if (origins.length == 0) {
            origins = new String[] {"http://localhost:*", "http://127.0.0.1:*"};
        }
        registry.addMapping("/api/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600L);
    }

    /**
     * traceId 过滤器注册（最高优先级，保证后续日志都带 traceId）。
     *
     * @return 过滤器注册
     */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> registration =
                new FilterRegistrationBean<TraceIdFilter>(new TraceIdFilter());
        registration.addUrlPatterns("/*");
        registration.setName("traceIdFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * SPA 路由回落过滤器（前端 BrowserRouter 刷新二级路径不 404）。
     *
     * <p>顺序在 traceId 之后、其余过滤器之前；接口路径不参与回落。
     *
     * @return 过滤器注册
     */
    @Bean
    public FilterRegistrationBean<SpaFallbackFilter> spaFallbackFilter() {
        FilterRegistrationBean<SpaFallbackFilter> registration =
                new FilterRegistrationBean<SpaFallbackFilter>(new SpaFallbackFilter());
        registration.addUrlPatterns("/*");
        registration.setName("spaFallbackFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
