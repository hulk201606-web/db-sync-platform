package com.dbsync.cdc.sdk;

/**
 * 快照拉取传输端口（把"怎么发 HTTP"与"怎么算路由"分开）。
 *
 * <p>存在的唯一理由是<b>可测性</b>：{@link RouteSnapshotClient} 的核心逻辑是
 * "版本单调 + 灰度稳定 + 失败沿用旧快照"，这些都能用一段字符串喂进去验证，
 * 不需要真的起一个 HTTP 服务。生产环境由 {@link HttpSnapshotTransport} 实现。
 */
public interface SnapshotTransport {

    /**
     * 拉取一次快照响应体。
     *
     * @param url    完整 URL（已含 {@code taskId} 查询参数）
     * @param token  服务令牌（放 {@code X-DBSync-Token} 头）
     * @return 响应体 JSON 文本（形如 {@code {"code":"OK","data":{...}}}）
     * @throws Exception 网络/协议错误（由调用方统一降级为"沿用旧快照"）
     */
    String fetch(String url, String token) throws Exception;
}
