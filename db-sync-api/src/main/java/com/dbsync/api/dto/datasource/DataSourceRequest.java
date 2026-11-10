package com.dbsync.api.dto.datasource;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * 数据源新建/编辑请求（PRD DS-01）。
 *
 * @param name           数据源名（唯一）
 * @param dbType         数据库类型
 * @param env            环境（PROD 会触发更严格的规则）
 * @param host           主机
 * @param port           端口
 * @param database       库名
 * @param schema         默认 schema
 * @param params         附加 JDBC 参数
 * @param username       用户名
 * @param password       明文口令（仅入参；落库前 AES-GCM 加密，响应中永不回显）
 * @param identifierCase 标识符大小写策略
 * @param poolMaxSize    连接池上限
 * @param poolMinIdle    连接池最小空闲
 * @param fetchSize      流式读 fetchSize（R2）
 */
public record DataSourceRequest(
        @NotBlank(message = "名称不能为空")
        @Size(max = 64, message = "名称长度需 ≤64")
        String name,
        @NotNull(message = "数据库类型不能为空")
        String dbType,
        String env,
        @NotBlank(message = "主机不能为空")
        String host,
        @Min(value = 1, message = "端口需 ≥1")
        @Max(value = 65535, message = "端口需 ≤65535")
        int port,
        String database,
        String schema,
        Map<String, String> params,
        @NotBlank(message = "用户名不能为空")
        String username,
        String password,
        String identifierCase,
        @Min(value = 1, message = "连接池上限需 ≥1")
        @Max(value = 200, message = "连接池上限需 ≤200（防止打爆目标库）")
        Integer poolMaxSize,
        Integer poolMinIdle,
        Integer fetchSize) {
}
