package com.dbsync.common.model;

import java.time.Instant;

/**
 * 实例信息（不可变）。{@code id} 形如 {@code host:port#uuid}。
 */
public final class InstanceInfo {

    private final String id;
    private final String host;
    private final int port;
    private final String version;
    private final String state;
    private final Instant startedAt;
    private final Instant heartbeatAt;
    private final int shardCount;

    /**
     * 构造。
     *
     * @param id          实例 ID
     * @param host        主机
     * @param port        端口
     * @param version     版本
     * @param state       状态（UP/DRAINING/DOWN）
     * @param startedAt   启动时间
     * @param heartbeatAt 心跳时间
     * @param shardCount  持有分片数
     */
    public InstanceInfo(String id, String host, int port, String version, String state,
                        Instant startedAt, Instant heartbeatAt, int shardCount) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.version = version;
        this.state = state;
        this.startedAt = startedAt;
        this.heartbeatAt = heartbeatAt;
        this.shardCount = shardCount;
    }

    /** @return 实例 ID */
    public String getId() {
        return id;
    }

    /** @return 主机 */
    public String getHost() {
        return host;
    }

    /** @return 端口 */
    public int getPort() {
        return port;
    }

    /** @return 版本 */
    public String getVersion() {
        return version;
    }

    /** @return 状态 */
    public String getState() {
        return state;
    }

    /** @return 启动时间 */
    public Instant getStartedAt() {
        return startedAt;
    }

    /** @return 心跳时间 */
    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    /** @return 持有分片数 */
    public int getShardCount() {
        return shardCount;
    }

    @Override
    public String toString() {
        return "Instance[" + id + "," + state + "]";
    }
}
