package com.dbsync.server.config;

import com.dbsync.api.dto.common.ApiResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API 目录（自描述接口清单）。
 *
 * <p>为什么不引 springdoc：依赖白名单里没有它，引入即违约。平台只需要"人能看懂的
 * 接口清单"给运维/联调，因此用一个只读端点返回结构化目录（含权限要求），
 * 比伪装成 OpenAPI 更诚实。
 */
@Configuration
public class OpenApiConfig {

    /**
     * 接口目录（不可变）。
     *
     * @return 目录
     */
    @Bean
    public ApiCatalog apiCatalog() {
        List<Map<String, Object>> endpoints = new ArrayList<>();
        endpoints.add(endpoint("POST", "/api/auth/login", "登录（无需令牌）", "-"));
        endpoints.add(endpoint("POST", "/api/auth/logout", "登出", "已登录"));
        endpoints.add(endpoint("POST", "/api/auth/password", "修改自身口令", "已登录"));
        endpoints.add(endpoint("GET", "/api/datasources", "数据源列表", "DS_MANAGE"));
        endpoints.add(endpoint("POST", "/api/datasources", "新建数据源", "DS_MANAGE"));
        endpoints.add(endpoint("POST", "/api/datasources/test", "未保存配置的连接测试（草稿）", "DS_MANAGE"));
        endpoints.add(endpoint("DELETE", "/api/datasources/{id}", "删除数据源（引用校验）", "DS_MANAGE"));
        endpoints.add(endpoint("GET", "/api/datasources/{id}", "数据源详情", "DS_MANAGE"));
        endpoints.add(endpoint("PUT", "/api/datasources/{id}", "编辑数据源", "DS_MANAGE"));
        endpoints.add(endpoint("GET", "/api/datasources/{id}/tables", "表清单", "DS_MANAGE"));
        endpoints.add(endpoint("GET", "/api/datasources/{id}/tables/{table}", "单表元数据（列/主键/索引）", "DS_MANAGE"));
        endpoints.add(endpoint("POST", "/api/datasources/{id}/test", "连接测试", "DS_MANAGE"));
        endpoints.add(endpoint("GET", "/api/tasks", "任务列表", "TASK_VIEW"));
        endpoints.add(endpoint("POST", "/api/tasks", "新建任务", "TASK_EDIT"));
        endpoints.add(endpoint("DELETE", "/api/tasks/{id}", "删除任务（软删除）", "TASK_EDIT"));
        endpoints.add(endpoint("GET", "/api/tasks/{id}", "任务详情", "TASK_VIEW"));
        endpoints.add(endpoint("PUT", "/api/tasks/{id}", "编辑任务（groupCount 不可改）", "TASK_EDIT"));
        endpoints.add(endpoint("POST", "/api/tasks/{id}/state", "状态动作 READY/ARCHIVE（ARCHIVE 需理由）", "TASK_EXEC"));
        endpoints.add(endpoint("POST", "/api/tasks/{taskId}/compare", "发起比对", "COMPARE_RUN"));
        endpoints.add(endpoint("POST", "/api/tasks/{taskId}/precheck", "预检", "TASK_EDIT"));
        endpoints.add(endpoint("GET", "/api/tasks/{taskId}/mappings", "映射列表", "TASK_VIEW"));
        endpoints.add(endpoint("POST", "/api/tasks/{taskId}/mappings/generate", "生成映射与 DDL", "TASK_EDIT"));
        endpoints.add(endpoint("GET", "/api/tasks/{taskId}/mappings/{mid}", "映射详情（表+列）", "TASK_VIEW"));
        endpoints.add(endpoint("PUT", "/api/tasks/{taskId}/mappings/{mid}/columns/{col}", "人工覆盖列类型（记 override_by）", "TASK_EDIT"));
        endpoints.add(endpoint("PUT", "/api/tasks/{taskId}/mappings/{mid}/conflict-policy", "按表设置冲突策略（SOURCE_WINS/TARGET_WINS/TIMESTAMP_NEWER）", "TASK_EDIT"));
        endpoints.add(endpoint("GET", "/api/tasks/{taskId}/mappings/{mid}/ddl", "DDL 预览（含风险标签）", "TASK_VIEW"));
        endpoints.add(endpoint("GET", "/api/tasks/{taskId}/mappings/{mid}/ddl-diff", "DDL 差异对比（源/生成/目标现有结构）", "TASK_VIEW"));
        endpoints.add(endpoint("POST", "/api/tasks/{taskId}/migration/cancel", "取消（幂等）", "TASK_EXEC"));
        endpoints.add(endpoint("POST", "/api/tasks/{taskId}/migration/limit", "调整限速（运行中热生效，0 不限速）", "TASK_EXEC"));
        endpoints.add(endpoint("POST", "/api/tasks/{taskId}/migration/pause", "暂停（幂等）", "TASK_EXEC"));
        endpoints.add(endpoint("GET", "/api/tasks/{taskId}/migration/progress", "迁移进度", "TASK_VIEW"));
        endpoints.add(endpoint("POST", "/api/tasks/{taskId}/migration/resume", "恢复（幂等）", "TASK_EXEC"));
        endpoints.add(endpoint("POST", "/api/tasks/{taskId}/migration/start", "启动迁移", "TASK_EXEC"));
        endpoints.add(endpoint("POST", "/api/tasks/{taskId}/migration/verify", "行数核验", "TASK_EXEC"));
        endpoints.add(endpoint("GET", "/api/tasks/{taskId}/shards", "分片清单（状态过滤+分页，上限 1000）", "TASK_VIEW"));
        endpoints.add(endpoint("GET", "/api/tasks/{taskId}/tables/progress", "按表进度（分片聚合，含百分比）", "TASK_VIEW"));
        endpoints.add(endpoint("GET", "/api/tasks/{taskId}/cdc/changes", "按主键追溯变更", "TASK_VIEW"));
        endpoints.add(endpoint("GET", "/api/tasks/{taskId}/cdc/latency", "增量延迟/积压", "TASK_VIEW"));
        endpoints.add(endpoint("PUT", "/api/tasks/{taskId}/cdc/mode", "切换增量模式", "TASK_EDIT"));
        endpoints.add(endpoint("GET", "/api/tasks/{taskId}/cdc/runtime", "增量消费运行态（本实例）", "TASK_VIEW"));
        endpoints.add(endpoint("DELETE", "/api/tasks/{taskId}/cdc/triggers", "卸载触发器", "TASK_EXEC"));
        endpoints.add(endpoint("POST", "/api/tasks/{taskId}/cdc/triggers/install", "部署触发器", "TASK_EXEC"));
        endpoints.add(endpoint("GET", "/api/compare/{jobId}", "比对作业详情", "TASK_VIEW"));
        endpoints.add(endpoint("POST", "/api/compare/{jobId}/cancel", "取消比对作业", "COMPARE_RUN"));
        endpoints.add(endpoint("GET", "/api/compare/{jobId}/diffs", "差异分页", "TASK_VIEW"));
        endpoints.add(endpoint("POST", "/api/compare/{jobId}/repair/fix-source", "反向修复源库（双人）", "COMPARE_FIX_SOURCE"));
        endpoints.add(endpoint("POST", "/api/compare/{jobId}/repair/replay", "重放到目标", "COMPARE_RUN"));
        endpoints.add(endpoint("GET", "/api/compare/{jobId}/report", "报告", "TASK_VIEW"));
        endpoints.add(endpoint("GET", "/api/compare/{jobId}/report/export", "CSV 导出", "TASK_VIEW"));
        endpoints.add(endpoint("GET", "/api/compare/{jobId}/summary", "差异分类与块统计", "TASK_VIEW"));
        endpoints.add(endpoint("POST", "/api/cutover/plans", "创建切流计划", "CUTOVER_ADVANCE"));
        endpoints.add(endpoint("GET", "/api/cutover/plans/by-task/{taskId}", "按任务查切流计划", "CUTOVER_VIEW"));
        endpoints.add(endpoint("GET", "/api/cutover/plans/{id}", "切流计划详情（状态/步骤/路由版本）", "CUTOVER_VIEW"));
        endpoints.add(endpoint("POST", "/api/cutover/plans/{id}/advance", "推进一步", "CUTOVER_ADVANCE"));
        endpoints.add(endpoint("POST", "/api/cutover/plans/{id}/dry-run", "演练（不写状态）", "CUTOVER_ADVANCE"));
        endpoints.add(endpoint("POST", "/api/cutover/plans/{id}/gate", "评估 Gate", "CUTOVER_ADVANCE"));
        endpoints.add(endpoint("POST", "/api/cutover/plans/{id}/rollback", "回滚", "CUTOVER_ROLLBACK"));
        endpoints.add(endpoint("POST", "/api/cutover/plans/{id}/skip-gate", "跳过 Gate（三件套）", "SKIP_GATE"));
        endpoints.add(endpoint("GET", "/api/deadletters", "死信列表", "DEAD_LETTER_MANAGE"));
        endpoints.add(endpoint("GET", "/api/deadletters/groups", "死信按异常指纹分组", "DEAD_LETTER_MANAGE"));
        endpoints.add(endpoint("POST", "/api/deadletters/replay", "批量重放死信", "DEAD_LETTER_MANAGE"));
        endpoints.add(endpoint("POST", "/api/deadletters/{id}/discard", "丢弃死信（需理由≥5字）", "DEAD_LETTER_MANAGE"));
        endpoints.add(endpoint("POST", "/api/deadletters/{id}/replay", "重放死信", "DEAD_LETTER_MANAGE"));
        endpoints.add(endpoint("GET", "/api/monitor/overview", "大盘总览", "TASK_VIEW"));
        endpoints.add(endpoint("GET", "/api/system/alerts", "告警通道/规则/最近告警", "USER_MANAGE"));
        endpoints.add(endpoint("POST", "/api/system/alerts/test", "手动测试告警通道", "USER_MANAGE"));
        endpoints.add(endpoint("POST", "/api/system/alerts/{rule}/test", "按规则编码测试告警", "USER_MANAGE"));
        endpoints.add(endpoint("GET", "/api/system/audit", "审计查询", "TASK_VIEW"));
        endpoints.add(endpoint("GET", "/api/system/audit/export", "审计导出 CSV", "USER_MANAGE"));
        endpoints.add(endpoint("GET", "/api/system/users", "用户列表", "USER_MANAGE"));
        endpoints.add(endpoint("POST", "/api/system/users", "新建用户", "USER_MANAGE"));
        endpoints.add(endpoint("PUT", "/api/system/users/{username}/enabled", "启用/禁用用户", "USER_MANAGE"));
        endpoints.add(endpoint("POST", "/api/system/users/{username}/password", "管理员重置口令", "USER_MANAGE"));
        endpoints.add(endpoint("GET", "/api/sdk/route-snapshot", "业务侧 SDK 路由快照（无计划时返回全源兜底）", "TASK_VIEW"));
        endpoints.add(endpoint("POST", "/api/sdk/route-ack", "业务侧 SDK 上报已生效路由版本（单调递增）", "TASK_VIEW"));
        return new ApiCatalog(endpoints);
    }

    private static Map<String, Object> endpoint(String method, String path, String desc,
                                                String permission) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("method", method);
        row.put("path", path);
        row.put("description", desc);
        row.put("permission", permission);
        return row;
    }

    /**
     * 接口目录载体。
     */
    public static final class ApiCatalog {

        private final List<Map<String, Object>> endpoints;

        /**
         * 构造。
         *
         * @param endpoints 端点清单
         */
        public ApiCatalog(List<Map<String, Object>> endpoints) {
            this.endpoints = List.copyOf(endpoints);
        }

        /** @return 端点清单 */
        public List<Map<String, Object>> getEndpoints() {
            return endpoints;
        }
    }

    /**
     * 目录端点（{@code GET /api/openapi.json}，只读、无需令牌）。
     *
     * <p>// ADDED: 04 文档要求 {@code OpenApiConfig}，但白名单没有 springdoc，
     * 因此用一个自描述端点替代自动生成文档；内容即上面手工维护的清单。
     */
    @RestController
    public static class OpenApiController {

        private final ApiCatalog catalog;

        /**
         * 构造。
         *
         * @param catalog 目录
         */
        public OpenApiController(ApiCatalog catalog) {
            this.catalog = catalog;
        }

        /**
         * 接口目录。
         *
         * @return 目录
         */
        @GetMapping("/api/openapi.json")
        public ApiResponse index() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("service", "db-sync-platform");
            data.put("version", "1.0.0");
            data.put("authHeader", "X-DBSync-Token");
            data.put("endpoints", catalog.getEndpoints());
            return ApiResponse.ok(data);
        }
    }
}
