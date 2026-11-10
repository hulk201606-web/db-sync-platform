package com.dbsync.api.controller;

import com.dbsync.api.audit.Audited;
import com.dbsync.api.dto.common.ApiResponse;
import com.dbsync.api.dto.compare.CompareRunRequest;
import com.dbsync.api.dto.compare.RepairRequest;
import com.dbsync.api.security.CurrentUser;
import com.dbsync.api.security.Permission;
import com.dbsync.api.security.RequiresPermission;
import com.dbsync.api.service.CompareService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 比对 API（T37）。
 *
 * <p>权限：发起/重放 = {@link Permission#COMPARE_RUN}（OPERATOR 及以上）；
 * 反向修复 = {@link Permission#COMPARE_FIX_SOURCE}（ADMIN only，T37 验收 1）。
 */
@RestController
public class CompareController {

    private final CompareService compareService;

    /**
     * 构造。
     *
     * @param compareService 比对服务
     */
    public CompareController(CompareService compareService) {
        this.compareService = compareService;
    }

    /**
     * 发起比对。
     *
     * @param taskId 任务 ID
     * @param req    请求（可空）
     * @return 作业
     */
    @PostMapping("/api/tasks/{taskId}/compare")
    @RequiresPermission(Permission.COMPARE_RUN)
    @Audited(action = "COMPARE_RUN", targetType = "TASK")
    public ApiResponse run(@PathVariable("taskId") long taskId,
                           @RequestBody(required = false) CompareRunRequest req) {
        return ApiResponse.ok(compareService.run(taskId, req).toMap());
    }

    /**
     * 作业详情。
     *
     * @param jobId 作业 ID
     * @return 作业
     */
    @GetMapping("/api/compare/{jobId}")
    @RequiresPermission(Permission.TASK_VIEW)
    public ApiResponse job(@PathVariable("jobId") long jobId) {
        return ApiResponse.ok(compareService.job(jobId).toMap());
    }

    /**
     * 报告（HTML + 落盘路径）。
     *
     * @param jobId 作业 ID
     * @return 报告
     */
    @GetMapping("/api/compare/{jobId}/report")
    @RequiresPermission(Permission.TASK_VIEW)
    public ApiResponse report(@PathVariable("jobId") long jobId) {
        return ApiResponse.ok(compareService.report(jobId).toMap());
    }

    /**
     * 导出 CSV（{@code text/csv}）。
     *
     * @param jobId 作业 ID
     * @return CSV 响应
     */
    @GetMapping("/api/compare/{jobId}/report/export")
    @RequiresPermission(Permission.TASK_VIEW)
    public ResponseEntity<String> export(@PathVariable("jobId") long jobId) {
        String csv = compareService.exportCsv(jobId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"compare-" + jobId + ".csv\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(csv);
    }

    /**
     * 差异分页。
     *
     * @param jobId 作业 ID
     * @param page  页码
     * @param size  每页条数
     * @param type  类型过滤
     * @return 分页结果
     */
    @GetMapping("/api/compare/{jobId}/diffs")
    @RequiresPermission(Permission.TASK_VIEW)
    public ApiResponse diffs(@PathVariable("jobId") long jobId,
                             @RequestParam(value = "page", defaultValue = "1") int page,
                             @RequestParam(value = "size", defaultValue = "100") int size,
                             @RequestParam(value = "type", required = false) String type) {
        return ApiResponse.ok(compareService.diffs(jobId, page, size, type).toMap());
    }

    /**
     * 重放到目标（幂等）。
     *
     * @param jobId 作业 ID
     * @param req   请求
     * @return 修复结果
     */
    @PostMapping("/api/compare/{jobId}/repair/replay")
    @RequiresPermission(Permission.COMPARE_RUN)
    @Audited(action = "COMPARE_REPAIR_REPLAY", targetType = "COMPARE_JOB")
    public ApiResponse replay(@PathVariable("jobId") long jobId,
                              @RequestBody(required = false) RepairRequest req) {
        return ApiResponse.ok(compareService.replayToTarget(jobId, req).toMap());
    }

    /**
     * 反向修复源库（ADMIN + 理由 ≥10 字）。
     *
     * @param jobId 作业 ID
     * @param req   请求
     * @return 修复结果
     */
    @PostMapping("/api/compare/{jobId}/repair/fix-source")
    @RequiresPermission(Permission.COMPARE_FIX_SOURCE)
    @Audited(action = "COMPARE_REPAIR_FIX_SOURCE", targetType = "COMPARE_JOB")
    public ApiResponse fixSource(@PathVariable("jobId") long jobId,
                                 @RequestBody(required = false) RepairRequest req) {
        return ApiResponse.ok(
                compareService.fixToSource(jobId, req, CurrentUser.actorOrAnonymous()).toMap());
    }

    /**
     * 取消作业。
     *
     * @param jobId 作业 ID
     * @return 空响应
     */
    @PostMapping("/api/compare/{jobId}/cancel")
    @RequiresPermission(Permission.COMPARE_RUN)
    @Audited(action = "COMPARE_CANCEL", targetType = "COMPARE_JOB")
    public ApiResponse cancel(@PathVariable("jobId") long jobId) {
        compareService.cancel(jobId);
        return ApiResponse.ok();
    }

    /**
     * 概览（差异分类 + 块统计）。
     *
     * @param jobId 作业 ID
     * @return 概览
     */
    @GetMapping("/api/compare/{jobId}/summary")
    @RequiresPermission(Permission.TASK_VIEW)
    public ApiResponse summary(@PathVariable("jobId") long jobId) {
        return ApiResponse.ok(compareService.summary(jobId));
    }
}
