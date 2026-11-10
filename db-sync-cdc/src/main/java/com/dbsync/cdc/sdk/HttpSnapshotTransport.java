package com.dbsync.cdc.sdk;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 基于 JDK {@link HttpClient} 的快照传输实现（不引入任何 HTTP 客户端依赖）。
 *
 * <p>为什么用 JDK 自带而不是 OkHttp/Apache HttpClient：SDK 要嵌进业务应用，
 * 每多一个传递依赖就多一份版本冲突风险；JDK 11+ 的 {@code java.net.http} 足够。
 *
 * <p>线程安全：{@link HttpClient} 自身线程安全，本类无可变状态。
 */
public final class HttpSnapshotTransport implements SnapshotTransport {

    /** 认证头名称（与平台 {@code RbacInterceptor} 读取的头一致）。 */
    public static final String TOKEN_HEADER = "X-DBSync-Token";

    /** 默认请求超时。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2L);

    private final HttpClient httpClient;
    private final Duration timeout;

    /**
     * 构造（使用默认 HttpClient 与 2 秒超时）。
     */
    public HttpSnapshotTransport() {
        this(HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(), DEFAULT_TIMEOUT);
    }

    /**
     * 构造。
     *
     * @param httpClient 注入的客户端（可为 null → 新建）
     * @param timeout    单次请求超时（null → 2 秒）
     */
    public HttpSnapshotTransport(HttpClient httpClient, Duration timeout) {
        this.httpClient = httpClient == null
                ? HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build() : httpClient;
        this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }

    @Override
    public String fetch(String url, String token) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET();
        if (token != null && !token.isBlank()) {
            builder.header(TOKEN_HEADER, token);
        }
        HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("路由快照拉取失败 HTTP " + response.statusCode());
        }
        return response.body();
    }
}
