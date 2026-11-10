package com.dbsync.api.controller;

import com.dbsync.api.audit.Audited;
import com.dbsync.api.dto.cdc.CdcModeRequest;
import com.dbsync.api.dto.common.ApiResponse;
import com.dbsync.api.security.Permission;
import com.dbsync.api.security.RequiresPermission;
import com.dbsync.api.service.CdcService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 增量 API（T32：模式 / 延迟 / 触发器 / 追溯）。
 */
@RestController
@RequestMapping("/api/tasks/{taskId}/cdc")
public class CdcController {

    private final CdcService cdcService;

    /**
     * 构造。
     *
     * @param cdcService 增量服务
     */
    public CdcController(CdcService cdcService) {
        this.cdcService = cdcService;
    }

    /**
     * 切换增量模式（RUNNING 拒绝）。
     *
     * @param taskId 任务 ID
     * @param req    请求
     * @return 切换结果
     */
    @PutMapping("/mode")
    @RequiresPermission(Permission.TASK_EDIT)
    @Audited(action = "CDC_MODE_SWITCH", targetType = "TASK")
    public ApiResponse mode(@PathVariable("taskId") long taskId,
                            @RequestBody(required = false) CdcModeRequest req) {
        return ApiResponse.ok(cdcService.switchMode(taskId, req).toMap());
    }

    /**
     * 延迟/积压/失败率。
     *
     * @param taskId 任务 ID
     * @return 指标
     */
    @GetMapping("/latency")
    @RequiresPermission(Permission.TASK_VIEW)
    public ApiResponse latency(@PathVariable("taskId") long taskId) {
        return ApiResponse.ok(cdcService.latency(taskId).toMap());
    }

    /**
     * 增量消费运行态（本实例视角，<b>只读</b>）。
     *
     * <p>返回字段：{@code taskId} / {@code running}（本实例是否有该任务的消费会话）/
     * {@code consumerConcurrency} / {@code startedAt}（ISO-8601）/
     * {@code ownerInstance}（消费者 ID = 组租约持有者）/ {@code lastError}（最近一次启动失败摘要）。
     *
     * @param taskId 任务 ID
     * @return 运行态
     */
    @GetMapping("/runtime")
    @RequiresPermission(Permission.TASK_VIEW)
    public ApiResponse runtime(@PathVariable("taskId") long taskId) {
        return ApiResponse.ok(cdcService.runtime(taskId).toMap());
    }

    /**
     * 部署触发器。
     *
     * @param taskId 任务 ID
     * @return 部署结果
     */
    @PostMapping("/triggers/install")
    @RequiresPermission(Permission.TASK_EXEC)
    @Audited(action = "CDC_TRIGGER_INSTALL", targetType = "TASK")
    public ApiResponse install(@PathVariable("taskId") long taskId) {
        return ApiResponse.ok(cdcService.installTriggers(taskId).toMap());
    }

    /**
     * 卸载触发器。
     *
     * @param taskId 任务 ID
     * @return 卸载结果
     */
    @DeleteMapping("/triggers")
    @RequiresPermission(Permission.TASK_EXEC)
    @Audited(action = "CDC_TRIGGER_UNINSTALL", targetType = "TASK")
    public ApiResponse uninstall(@PathVariable("taskId") long taskId) {
        return ApiResponse.ok(cdcService.uninstallTriggers(taskId).toMap());
    }

    /**
     * 按主键追溯最近变更。
     *
     * @param taskId 任务 ID
     * @param table  表名（可空）
     * @param pk     主键文本
     * @return 追溯结果
     */
    @GetMapping("/changes")
    @RequiresPermission(Permission.TASK_VIEW)
    public ApiResponse changes(@PathVariable("taskId") long taskId,
                               @RequestParam(value = "table", required = false) String table,
                               @RequestParam("pk") String pk) {
        return ApiResponse.ok(cdcService.trace(taskId, table, pk).toMap());
    }
}
