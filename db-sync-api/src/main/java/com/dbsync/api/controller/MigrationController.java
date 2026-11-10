package com.dbsync.api.controller;

import com.dbsync.api.audit.Audited;
import com.dbsync.api.dto.common.ApiResponse;
import com.dbsync.api.dto.migration.RateLimitRequest;
import com.dbsync.api.security.Permission;
import com.dbsync.api.security.RequiresPermission;
import com.dbsync.api.service.MigrationRateLimitService;
import com.dbsync.api.service.MigrationService;
import com.dbsync.api.service.ShardProgressService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 迁移 API（T27 + MIG-05/MIG-06）。
 *
 * <p>10 个端点：precheck / start / pause / resume / cancel / progress / verify
 * + <b>limit</b>（运行中改限速）+ <b>shards</b>（分片清单）+ <b>tables/progress</b>（按表进度）。
 * 权限按架构 §12.3：预检属"编辑"（TASK_EDIT），执行类属 TASK_EXEC，进度/清单只读（TASK_VIEW）。
 *
 * <p>后三个端点为什么必须新增：MIG-05 要求"运行中改限速"、MIG-06 要求
 * "任务→表→分片"三级进度，而此前平台只有任务级进度查询（{@code /migration/progress}），
 * 既没有任何限速写入口，也没有表级/分片级读入口。
 */
@RestController
@RequestMapping("/api/tasks/{taskId}")
public class MigrationController {

    private final MigrationService migrationService;
    private final MigrationRateLimitService rateLimitService;
    private final ShardProgressService shardProgressService;

    /**
     * 构造。
     *
     * @param migrationService     迁移服务（预检/启停/进度/核数）
     * @param rateLimitService     动态限速服务（MIG-05）
     * @param shardProgressService 表级与分片级进度服务（MIG-06）
     */
    public MigrationController(MigrationService migrationService,
                               MigrationRateLimitService rateLimitService,
                               ShardProgressService shardProgressService) {
        this.migrationService = migrationService;
        this.rateLimitService = rateLimitService;
        this.shardProgressService = shardProgressService;
    }

    /**
     * 预检。
     *
     * @param taskId 任务 ID
     * @return 预检结果
     */
    @PostMapping("/precheck")
    @RequiresPermission(Permission.TASK_EDIT)
    public ApiResponse precheck(@PathVariable("taskId") long taskId) {
        return ApiResponse.ok(migrationService.precheck(taskId).toMap());
    }

    /**
     * 启动。
     *
     * @param taskId 任务 ID
     * @return 动作结果
     */
    @PostMapping("/migration/start")
    @RequiresPermission(Permission.TASK_EXEC)
    @Audited(action = "MIGRATION_START", targetType = "TASK")
    public ApiResponse start(@PathVariable("taskId") long taskId) {
        return ApiResponse.ok(migrationService.start(taskId).toMap());
    }

    /**
     * 暂停（幂等）。
     *
     * @param taskId 任务 ID
     * @return 动作结果
     */
    @PostMapping("/migration/pause")
    @RequiresPermission(Permission.TASK_EXEC)
    @Audited(action = "MIGRATION_PAUSE", targetType = "TASK")
    public ApiResponse pause(@PathVariable("taskId") long taskId) {
        return ApiResponse.ok(migrationService.pause(taskId).toMap());
    }

    /**
     * 恢复（幂等）。
     *
     * @param taskId 任务 ID
     * @return 动作结果
     */
    @PostMapping("/migration/resume")
    @RequiresPermission(Permission.TASK_EXEC)
    @Audited(action = "MIGRATION_RESUME", targetType = "TASK")
    public ApiResponse resume(@PathVariable("taskId") long taskId) {
        return ApiResponse.ok(migrationService.resume(taskId).toMap());
    }

    /**
     * 取消（幂等）。
     *
     * @param taskId 任务 ID
     * @return 动作结果
     */
    @PostMapping("/migration/cancel")
    @RequiresPermission(Permission.TASK_EXEC)
    @Audited(action = "MIGRATION_CANCEL", targetType = "TASK")
    public ApiResponse cancel(@PathVariable("taskId") long taskId) {
        return ApiResponse.ok(migrationService.cancel(taskId).toMap());
    }

    /**
     * 进度（读缓存）。
     *
     * @param taskId 任务 ID
     * @return 进度
     */
    @GetMapping("/migration/progress")
    @RequiresPermission(Permission.TASK_VIEW)
    public ApiResponse progress(@PathVariable("taskId") long taskId) {
        return ApiResponse.ok(migrationService.progress(taskId).toMap());
    }

    /**
     * 核数。
     *
     * @param taskId 任务 ID
     * @return 核数结果
     */
    @PostMapping("/migration/verify")
    @RequiresPermission(Permission.TASK_EXEC)
    public ApiResponse verify(@PathVariable("taskId") long taskId) {
        return ApiResponse.ok(migrationService.verify(taskId).toMap());
    }

    /**
     * 运行中调整限速（MIG-05）。
     *
     * <p>请求体二选一：{@code {"rowsPerSec":10000}} 或
     * {@code {"readRowsPerSec":10000,"writeRowsPerSec":5000}}；{@code 0} 表示不限速。
     * 响应 {@code effective=IMMEDIATE} 表示本实例正在跑该任务且已热更新；
     * {@code NEXT_START} 表示只落库（下次启动生效）。
     *
     * @param taskId 任务 ID
     * @param req    限速请求
     * @return 结果
     */
    @PostMapping("/migration/limit")
    @RequiresPermission(Permission.TASK_EXEC)
    @Audited(action = "MIGRATION_LIMIT_CHANGE", targetType = "TASK")
    public ApiResponse limit(@PathVariable("taskId") long taskId,
                             @RequestBody RateLimitRequest req) {
        return ApiResponse.ok(rateLimitService.changeLimit(taskId, req).toMap());
    }

    /**
     * 分片清单（MIG-06 分片级进度）。
     *
     * @param taskId 任务 ID
     * @param state  状态过滤（可空，可选 PENDING/CLAIMED/RUNNING/SUCCESS/FAILED/DEAD/PAUSED）
     * @param page   页码（1 起，非法值归一化到 1）
     * @param size   页大小（默认 200，上限 1000）
     * @return 分页结果
     */
    @GetMapping("/shards")
    @RequiresPermission(Permission.TASK_VIEW)
    public ApiResponse shards(@PathVariable("taskId") long taskId,
                              @RequestParam(value = "state", required = false) String state,
                              @RequestParam(value = "page", defaultValue = "1") int page,
                              @RequestParam(value = "size", defaultValue = "200") int size) {
        return ApiResponse.ok(shardProgressService.shards(taskId, state, page, size).toMap());
    }

    /**
     * 按表进度（MIG-06 表级）。
     *
     * @param taskId 任务 ID
     * @return 各表进度
     */
    @GetMapping("/tables/progress")
    @RequiresPermission(Permission.TASK_VIEW)
    public ApiResponse tablesProgress(@PathVariable("taskId") long taskId) {
        return ApiResponse.ok(shardProgressService.tablesProgress(taskId).toMap());
    }
}
