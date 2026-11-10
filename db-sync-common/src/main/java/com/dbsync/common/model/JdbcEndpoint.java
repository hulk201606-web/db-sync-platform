package com.dbsync.common.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 数据源连接端点（不含密码）。
 *
 * <p>{@link #toString()} **只**输出 {@code host:port/database}，绝不含账号密码，
 * 因此可安全写日志（架构 §12.1 硬约束 3）。
 */
public final class JdbcEndpoint {

    private final String host;
    private final int port;
    private final String database;
    private final String schema;
    private final Map<String, String> params;

    /**
     * 构造。
     *
     * @param host     主机，不可空
     * @param port     端口，1..65535
     * @param database 数据库名（Oracle 为 SID/Service），可空
     * @param schema   默认 schema，可空
     * @param params   追加连接参数，null 视为空
     */
    public JdbcEndpoint(String host, int port, String database, String schema, Map<String, String> params) {
        this.host = Objects.requireNonNull(host, "host");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("端口非法：" + port);
        }
        this.port = port;
        this.database = database;
        this.schema = schema;
        this.params = params == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /**
     * @return 主机
     */
    public String getHost() {
        return host;
    }

    /**
     * @return 端口
     */
    public int getPort() {
        return port;
    }

    /**
     * @return 数据库名，可能为 null
     */
    public String getDatabase() {
        return database;
    }

    /**
     * @return 默认 schema，可能为 null
     */
    public String getSchema() {
        return schema;
    }

    /**
     * @return 不可变连接参数
     */
    public Map<String, String> getParams() {
        return params;
    }

    /**
     * 参数串（{@code k=v&k2=v2}），无参数时返回空串。
     *
     * @return 参数串
     */
    public String paramsQuery() {
        if (params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * 脱敏表示：{@code host:port/database}。
     *
     * @return 脱敏串
     */
    @Override
    public String toString() {
        return host + ":" + port + (database == null ? "" : "/" + database);
    }
}
