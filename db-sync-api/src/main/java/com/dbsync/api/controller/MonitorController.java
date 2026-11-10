package com.dbsync.api.controller;

import com.dbsync.api.dto.common.ApiResponse;
import com.dbsync.api.security.Permission;
import com.dbsync.api.security.RequiresPermission;
import com.dbsync.api.service.MonitorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 监控大盘 API（T45：静态页 {@code dashboard.html} 轮询本接口）。
 *
 * <p>纯只读，VIEWER 即可访问（架构 §12.3 "查看大盘"）。
 * 静态页用原生 {@code fetch} 轮询，不引入前端构建（Q-A 决策）。
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final MonitorService monitorService;

    /**
     * 构造。
     *
     * @param monitorService 大盘服务
     */
    public MonitorController(MonitorService monitorService) {
        this.monitorService = monitorService;
    }

    /**
     * 总览。
     *
     * @return 总览
     */
    @GetMapping("/overview")
    @RequiresPermission(Permission.TASK_VIEW)
    public ApiResponse overview() {
        return ApiResponse.ok(monitorService.overview().toMap());
    }
}
