package com.dbsync.api.controller;

import com.dbsync.api.audit.Audited;
import com.dbsync.api.dto.common.ApiResponse;
import com.dbsync.api.dto.migration.DeadLetterView;
import com.dbsync.api.dto.migration.ReplayRequest;
import com.dbsync.api.security.CurrentUser;
import com.dbsync.api.security.Permission;
import com.dbsync.api.security.RequiresPermission;
import com.dbsync.api.service.DeadLetterAdminService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 死信 API（T45：列表 / 分组 / 重放 / 丢弃）。
 *
 * <p>权限：全部 {@link Permission#DEAD_LETTER_MANAGE}（OPERATOR 及以上）。
 */
@RestController
@RequestMapping("/api/deadletters")
public class DeadLetterController {

    private final DeadLetterAdminService deadLetterAdminService;

    /**
     * 构造。
     *
     * @param deadLetterAdminService 死信管理服务
     */
    public DeadLetterController(DeadLetterAdminService deadLetterAdminService) {
        this.deadLetterAdminService = deadLetterAdminService;
    }

    /**
     * 列表。
     *
     * @param taskId 任务 ID
     * @param status 状态（可空，默认 OPEN）
     * @param limit  上限
     * @return 死信列表
     */
    @GetMapping
    @RequiresPermission(Permission.DEAD_LETTER_MANAGE)
    public ApiResponse list(@RequestParam("taskId") long taskId,
                            @RequestParam(value = "status", required = false) String status,
                            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        List<Object> data = new ArrayList<>();
        for (DeadLetterView view : deadLetterAdminService.list(taskId, status, limit)) {
            data.add(view.toMap());
        }
        return ApiResponse.ok(data);
    }

    /**
     * 按错误类型分组。
     *
     * @param taskId 任务 ID
     * @param status 状态（可空）
     * @return 分组计数
     */
    @GetMapping("/groups")
    @RequiresPermission(Permission.DEAD_LETTER_MANAGE)
    public ApiResponse groups(@RequestParam("taskId") long taskId,
                              @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(deadLetterAdminService.groups(taskId, status));
    }

    /**
     * 重放单条。
     *
     * @param id 死信 ID
     * @return 结果
     */
    @PostMapping("/{id}/replay")
    @RequiresPermission(Permission.DEAD_LETTER_MANAGE)
    @Audited(action = "DEAD_LETTER_REPLAY", targetType = "DEAD_LETTER")
    public ApiResponse replay(@PathVariable("id") long id) {
        return ApiResponse.ok(deadLetterAdminService.replay(id, CurrentUser.actorOrAnonymous()));
    }

    /**
     * 批量重放。
     *
     * @param req 请求
     * @return 汇总
     */
    @PostMapping("/replay")
    @RequiresPermission(Permission.DEAD_LETTER_MANAGE)
    @Audited(action = "DEAD_LETTER_REPLAY_BATCH", targetType = "DEAD_LETTER")
    public ApiResponse replayBatch(@RequestBody(required = false) ReplayRequest req) {
        return ApiResponse.ok(
                deadLetterAdminService.replayBatch(req, CurrentUser.actorOrAnonymous()));
    }

    /**
     * 丢弃（需理由）。
     *
     * @param id  死信 ID
     * @param req 请求（使用 reason 字段）
     * @return 结果
     */
    @PostMapping("/{id}/discard")
    @RequiresPermission(Permission.DEAD_LETTER_MANAGE)
    @Audited(action = "DEAD_LETTER_DISCARD", targetType = "DEAD_LETTER")
    public ApiResponse discard(@PathVariable("id") long id,
                               @RequestBody(required = false) ReplayRequest req) {
        String reason = req == null ? null : req.reason();
        return ApiResponse.ok(deadLetterAdminService.discard(id, reason,
                CurrentUser.actorOrAnonymous()));
    }

}
