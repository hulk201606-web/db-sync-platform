package com.dbsync.api.dto.datasource;

import com.dbsync.common.model.DataSourceDef;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据源视图（响应）。
 *
 * <p><b>口令永不出现在响应里</b>（SEC-04 / 架构 §12.1）：只回 {@code hasPassword} 布尔，
 * 连密文都不返回，避免密文被离线爆破。
 *
 * @param id             数据源 ID
 * @param name           名称
 * @param dbType         类型
 * @param env            环境
 * @param host           主机
 * @param port           端口
 * @param database       库名
 * @param schema         默认 schema
 * @param username       用户名
 * @param identifierCase 标识符策略
 * @param poolMaxSize    池上限
 * @param poolMinIdle    池最小空闲
 * @param fetchSize      fetchSize
 * @param healthStatus   健康状态（UP/DOWN/UNKNOWN）
 * @param hasPassword    是否已配置口令
 */
public record DataSourceView(long id, String name, String dbType, String env, String host,
                             int port, String database, String schema, String username,
                             String identifierCase, int poolMaxSize, int poolMinIdle,
                             int fetchSize, String healthStatus, boolean hasPassword) {

    /**
     * 从模型转换。
     *
     * @param def 数据源定义
     * @return 视图
     */
    public static DataSourceView of(DataSourceDef def) {
        return new DataSourceView(def.getId(), def.getName(), def.getDbType().name(),
                def.getEnv().name(), def.getEndpoint().getHost(), def.getEndpoint().getPort(),
                def.getEndpoint().getDatabase(), def.getEndpoint().getSchema(), def.getUsername(),
                def.getIdentifierCase().name(), def.getPoolMaxSize(), def.getPoolMinIdle(),
                def.getFetchSize(), def.getHealthStatus(),
                def.getPasswordCipher() != null && !def.getPasswordCipher().isBlank());
    }

    /**
     * @return 有序 Map（前端直接渲染）
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("dbType", dbType);
        map.put("env", env);
        map.put("host", host);
        map.put("port", port);
        map.put("database", database);
        map.put("schema", schema);
        map.put("username", username);
        map.put("identifierCase", identifierCase);
        map.put("poolMaxSize", poolMaxSize);
        map.put("poolMinIdle", poolMinIdle);
        map.put("fetchSize", fetchSize);
        map.put("healthStatus", healthStatus);
        map.put("hasPassword", hasPassword);
        return map;
    }
}
