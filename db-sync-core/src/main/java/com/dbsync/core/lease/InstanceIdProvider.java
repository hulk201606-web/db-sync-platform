package com.dbsync.core.lease;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * 实例 ID 提供者。
 *
 * <p>格式：{@code host:port#shortUuid}（架构 §4.2.2）。
 * 为什么要带 uuid 后缀：容器化部署时 host:port 可能被复用（前一个实例刚被 kill，
 * 新实例抢到同一端口），若 ID 相同，旧实例残留的租约会被新实例"误认为自己的"，
 * 从而跳过 {@code lease_expire_at} 检查 → 两个进程同时写同一分片。
 * 加 uuid 让"每次都算新实例"，宁可多等 30s 租约，也不冒并发写风险。
 */
public final class InstanceIdProvider {

    private final String instanceId;
    private final String host;
    private final int port;

    /**
     * 构造。
     *
     * @param configuredHost 配置的主机名（null/空则自动探测）
     * @param port           服务端口
     */
    public InstanceIdProvider(String configuredHost, int port) {
        this.host = configuredHost == null || configuredHost.isBlank() ? detectHost()
                : configuredHost;
        this.port = port <= 0 ? 8080 : port;
        this.instanceId = this.host + ":" + this.port + "#"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** @return 实例 ID（本进程内唯一且稳定） */
    public String instanceId() {
        return instanceId;
    }

    /** @return 主机名 */
    public String host() {
        return host;
    }

    /** @return 端口 */
    public int port() {
        return port;
    }

    /** @return 进程 PID（运维定位用） */
    public static long pid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        int at = name.indexOf('@');
        String pidPart = at > 0 ? name.substring(0, at) : name;
        try {
            return Long.parseLong(pidPart);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /**
     * 探测本机主机名（失败退化为 localhost，绝不因为拿不到 hostname 而启动失败）。
     *
     * @return 主机名
     */
    private static String detectHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }

    @Override
    public String toString() {
        return instanceId;
    }
}
