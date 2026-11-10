package com.dbsync.api.controller;

import com.dbsync.api.audit.Audited;
import com.dbsync.api.dto.common.ApiResponse;
import com.dbsync.api.dto.cutover.CutoverPlanCreateRequest;
import com.dbsync.api.dto.cutover.CutoverPlanView;
import com.dbsync.api.dto.cutover.RollbackRequest;
import com.dbsync.api.dto.cutover.SkipGateRequest;
import com.dbsync.api.security.CurrentUser;
import com.dbsync.api.security.Permission;
import com.dbsync.api.security.RequiresPermission;
import com.dbsync.api.service.CutoverService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 切流 API（T41：7 个端点）。
 *
 * <p>权限（架构 §12.3）：创建/Gate/推进/跳过/回滚/dry-run 全部 ADMIN；
 * 只有查询走 {@link Permission#CUTOVER_VIEW}（VIEWER 及以上）。
 */
@RestController
@RequestMapping("/api/cutover/plans")
public class CutoverController {

    private final CutoverService cutoverService;

    /**
     * 构造。
     *
     * @param cutoverService 切流服务
     */
    public CutoverController(CutoverService cutoverService) {
        this.cutoverService = cutoverService;
    }

    /**
     * 创建计划。
     *
     * @param req 请求
     * @return 计划
     */
    @PostMapping
    @RequiresPermission(Permission.CUTOVER_ADVANCE)
    @Audited(action = "CUTOVER_PLAN_CREATE", targetType = "CUTOVER_PLAN")
    public ApiResponse create(@Valid @RequestBody CutoverPlanCreateRequest req) {
        return ApiResponse.ok(
                cutoverService.create(req, CurrentUser.actorOrAnonymous()).toMap());
    }

    /**
     * 计划详情（含步骤历史）。
     *
     * @param id 计划 ID
     * @return 详情
     */
    @GetMapping("/{id}")
    @RequiresPermission(Permission.CUTOVER_VIEW)
    public ApiResponse detail(@PathVariable("id") long id) {
        return ApiResponse.ok(cutoverService.detail(id));
    }

    /**
     * 评估 Gate。
     *
     * @param id 计划 ID
     * @return Gate 结果
     */
    @PostMapping("/{id}/gate")
    @RequiresPermission(Permission.CUTOVER_ADVANCE)
    public ApiResponse gate(@PathVariable("id") long id) {
        return ApiResponse.ok(cutoverService.gate(id).toMap());
    }

    /**
     * 推进一步。
     *
     * @param id 计划 ID
     * @return 步骤
     */
    @PostMapping("/{id}/advance")
    @RequiresPermission(Permission.CUTOVER_ADVANCE)
    @Audited(action = "CUTOVER_ADVANCE", targetType = "CUTOVER_PLAN")
    public ApiResponse advance(@PathVariable("id") long id) {
        return ApiResponse.ok(
                cutoverService.advance(id, CurrentUser.actorOrAnonymous()).toMap());
    }

    /**
     * 跳过 Gate 并推进（三件套）。
     *
     * @param id  计划 ID
     * @param req 请求
     * @return 步骤
     */
    @PostMapping("/{id}/skip-gate")
    @RequiresPermission(Permission.SKIP_GATE)
    @Audited(action = "CUTOVER_SKIP_GATE", targetType = "CUTOVER_PLAN")
    public ApiResponse skipGate(@PathVariable("id") long id,
                                @RequestBody(required = false) SkipGateRequest req) {
        CurrentUser user = CurrentUser.require();
        return ApiResponse.ok(cutoverService.skipGate(id, req, user.getUsername(),
                user.getRole().code()).toMap());
    }

    /**
     * 回滚。
     *
     * @param id  计划 ID
     * @param req 请求
     * @return 步骤
     */
    @PostMapping("/{id}/rollback")
    @RequiresPermission(Permission.CUTOVER_ROLLBACK)
    @Audited(action = "CUTOVER_ROLLBACK", targetType = "CUTOVER_PLAN")
    public ApiResponse rollback(@PathVariable("id") long id,
                                @RequestBody(required = false) RollbackRequest req) {
        return ApiResponse.ok(
                cutoverService.rollback(id, req, CurrentUser.actorOrAnonymous()).toMap());
    }

    /**
     * dry-run（不写状态）。
     *
     * @param id 计划 ID
     * @return dry-run 报告
     */
    @PostMapping("/{id}/dry-run")
    @RequiresPermission(Permission.CUTOVER_ADVANCE)
    public ApiResponse dryRun(@PathVariable("id") long id) {
        return ApiResponse.ok(cutoverService.dryRun(id).toMap());
    }

    /**
     * 某任务下的计划列表（T45 大盘用）。
     *
     * @param taskId 任务 ID
     * @return 计划列表
     */
    @GetMapping("/by-task/{taskId}")
    @RequiresPermission(Permission.CUTOVER_VIEW)
    public ApiResponse listByTask(@PathVariable("taskId") long taskId) {
        List<Object> data = new ArrayList<>();
        for (CutoverPlanView view : cutoverService.listByTask(taskId)) {
            data.add(view.toMap());
        }
        return ApiResponse.ok(data);
    }
}
