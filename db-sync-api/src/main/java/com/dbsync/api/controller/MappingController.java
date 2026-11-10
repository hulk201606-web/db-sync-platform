package com.dbsync.api.controller;

import com.dbsync.api.audit.Audited;
import com.dbsync.api.dto.common.ApiResponse;
import com.dbsync.api.dto.mapping.ColumnMappingView;
import com.dbsync.api.dto.mapping.ColumnOverrideRequest;
import com.dbsync.api.dto.mapping.DdlDiffView;
import com.dbsync.api.dto.mapping.DdlView;
import com.dbsync.api.dto.mapping.GenerateMappingRequest;
import com.dbsync.api.dto.mapping.MappingDetailView;
import com.dbsync.api.dto.mapping.MappingPolicyRequest;
import com.dbsync.api.dto.mapping.MappingView;
import com.dbsync.api.security.CurrentUser;
import com.dbsync.api.security.Permission;
import com.dbsync.api.security.RequiresPermission;
import com.dbsync.api.service.MappingService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 映射 API（T43 / PRD MAP-01~04）。
 *
 * <p>路径挂在任务下：{@code /api/tasks/{id}/mappings...}，因为映射必须属于某个任务。
 */
@RestController
@RequestMapping("/api/tasks/{taskId}/mappings")
public class MappingController {

    private final MappingService mappingService;

    /**
     * 构造。
     *
     * @param mappingService 映射服务
     */
    public MappingController(MappingService mappingService) {
        this.mappingService = mappingService;
    }

    /**
     * 列表。
     *
     * @param taskId 任务 ID
     * @return 映射列表
     */
    @GetMapping
    @RequiresPermission(Permission.TASK_VIEW)
    public ApiResponse list(@PathVariable("taskId") long taskId) {
        List<Object> data = new ArrayList<>();
        for (MappingView view : mappingService.list(taskId)) {
            data.add(view.toMap());
        }
        return ApiResponse.ok(data);
    }

    /**
     * 自动生成映射 + DDL。
     *
     * @param taskId 任务 ID
     * @param req    请求（可空）
     * @return 生成的映射列表
     */
    @PostMapping("/generate")
    @RequiresPermission(Permission.TASK_EDIT)
    @Audited(action = "MAPPING_GENERATE", targetType = "TASK")
    public ApiResponse generate(@PathVariable("taskId") long taskId,
                                @RequestBody(required = false) GenerateMappingRequest req) {
        List<Object> data = new ArrayList<>();
        for (MappingView view : mappingService.generate(taskId, req)) {
            data.add(view.toMap());
        }
        return ApiResponse.ok(data, "已生成 " + data.size() + " 张表映射");
    }

    /**
     * 详情（含列映射）。
     *
     * @param taskId 任务 ID
     * @param mid    映射 ID
     * @return 详情
     */
    @GetMapping("/{mid}")
    @RequiresPermission(Permission.TASK_VIEW)
    public ApiResponse detail(@PathVariable("taskId") long taskId,
                              @PathVariable("mid") long mid) {
        MappingDetailView detail = mappingService.detail(taskId, mid);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mapping", detail.mapping().toMap());
        data.put("columns", detail.columns());
        return ApiResponse.ok(data);
    }

    /**
     * DDL 预览。
     *
     * @param taskId 任务 ID
     * @param mid    映射 ID
     * @return DDL
     */
    @GetMapping("/{mid}/ddl")
    @RequiresPermission(Permission.TASK_VIEW)
    public ApiResponse ddl(@PathVariable("taskId") long taskId, @PathVariable("mid") long mid) {
        DdlView ddl = mappingService.ddl(taskId, mid);
        return ApiResponse.ok(ddl.toMap());
    }

    /**
     * DDL 差异对比（PRD MAP-05：源结构 / 生成 DDL / 目标库现有结构）。
     *
     * <p>与 {@code /ddl} 的区别：{@code /ddl} 回答"要建什么"，本端点回答"照做会有什么问题"——
     * 目标缺列、类型变窄（截断）、索引缺失。目标表不存在时返回
     * {@code targetExists=false} 且所有列 {@code NEW}，<b>不抛异常</b>。
     *
     * @param taskId 任务 ID
     * @param mid    映射 ID
     * @return 差异对比
     */
    @GetMapping("/{mid}/ddl-diff")
    @RequiresPermission(Permission.TASK_VIEW)
    public ApiResponse ddlDiff(@PathVariable("taskId") long taskId, @PathVariable("mid") long mid) {
        DdlDiffView diff = mappingService.ddlDiff(taskId, mid);
        return ApiResponse.ok(diff.toMap());
    }

    /**
     * 更新表级冲突策略（PRD CDC-06：三种策略按表可配）。
     *
     * <p>只接受 {@code SOURCE_WINS} / {@code TARGET_WINS} / {@code TIMESTAMP_NEWER}；
     * 非法值返回结构化 {@code E1001}（不是 500，也不静默兜底成默认策略）。
     *
     * @param taskId 任务 ID
     * @param mid    映射 ID
     * @param req    策略请求
     * @return 更新后的映射
     */
    @PutMapping("/{mid}/conflict-policy")
    @RequiresPermission(Permission.TASK_EDIT)
    @Audited(action = "MAPPING_CONFLICT_POLICY", targetType = "TASK")
    public ApiResponse conflictPolicy(@PathVariable("taskId") long taskId,
                                      @PathVariable("mid") long mid,
                                      @RequestBody(required = false) MappingPolicyRequest req) {
        String policy = req == null ? null : req.conflictPolicy();
        return ApiResponse.ok(mappingService.updateConflictPolicy(taskId, mid, policy));
    }

    /**
     * 人工覆盖列类型。
     *
     * @param taskId 任务 ID
     * @param mid    映射 ID
     * @param col    源列名
     * @param req    覆盖请求
     * @return 覆盖后的列
     */
    @PutMapping("/{mid}/columns/{col}")
    @RequiresPermission(Permission.TASK_EDIT)
    @Audited(action = "MAPPING_OVERRIDE_COLUMN", targetType = "TASK")
    public ApiResponse overrideColumn(@PathVariable("taskId") long taskId,
                                      @PathVariable("mid") long mid,
                                      @PathVariable("col") String col,
                                      @RequestBody(required = false) ColumnOverrideRequest req) {
        ColumnMappingView view = mappingService.overrideColumn(taskId, mid, col, req,
                CurrentUser.actorOrAnonymous());
        return ApiResponse.ok(view);
    }
}
