package com.dbsync.api.controller;

import com.dbsync.api.audit.Audited;
import com.dbsync.api.dto.common.ApiResponse;
import com.dbsync.api.dto.task.TaskActionRequest;
import com.dbsync.api.dto.task.TaskRequest;
import com.dbsync.api.dto.task.TaskView;
import com.dbsync.api.security.CurrentUser;
import com.dbsync.api.security.Permission;
import com.dbsync.api.security.RequiresPermission;
import com.dbsync.api.service.TaskService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务 API（T43 / PRD ORC-01）。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    /**
     * 构造。
     *
     * @param taskService 任务服务
     */
    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 列表。
     *
     * @return 任务列表
     */
    @GetMapping
    @RequiresPermission(Permission.TASK_VIEW)
    public ApiResponse list() {
        List<Object> data = new ArrayList<>();
        for (TaskView view : taskService.list()) {
            data.add(view.toMap());
        }
        return ApiResponse.ok(data);
    }

    /**
     * 详情。
     *
     * @param id 任务 ID
     * @return 任务
     */
    @GetMapping("/{id}")
    @RequiresPermission(Permission.TASK_VIEW)
    public ApiResponse get(@PathVariable("id") long id) {
        return ApiResponse.ok(taskService.get(id).toMap());
    }

    /**
     * 新建。
     *
     * @param req 请求
     * @return 新建的任务
     */
    @PostMapping
    @RequiresPermission(Permission.TASK_EDIT)
    @Audited(action = "TASK_CREATE", targetType = "TASK")
    public ApiResponse create(@Valid @RequestBody TaskRequest req) {
        return ApiResponse.ok(taskService.create(req, CurrentUser.actorOrAnonymous()).toMap());
    }

    /**
     * 编辑（{@code groupCount} 改动会被拒）。
     *
     * @param id  任务 ID
     * @param req 请求
     * @return 编辑后的任务
     */
    @PutMapping("/{id}")
    @RequiresPermission(Permission.TASK_EDIT)
    @Audited(action = "TASK_UPDATE", targetType = "TASK")
    public ApiResponse update(@PathVariable("id") long id, @Valid @RequestBody TaskRequest req) {
        return ApiResponse.ok(taskService.update(id, req, CurrentUser.actorOrAnonymous()).toMap());
    }

    /**
     * 删除（软删）。
     *
     * @param id 任务 ID
     * @return 空响应
     */
    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.TASK_EDIT)
    @Audited(action = "TASK_DELETE", targetType = "TASK")
    public ApiResponse delete(@PathVariable("id") long id) {
        taskService.delete(id, CurrentUser.actorOrAnonymous());
        return ApiResponse.ok();
    }

    /**
     * 状态动作（READY / ARCHIVE）。
     *
     * @param id  任务 ID
     * @param req 动作请求
     * @return 任务
     */
    @PostMapping("/{id}/state")
    @RequiresPermission(Permission.TASK_EXEC)
    @Audited(action = "TASK_STATE", targetType = "TASK")
    public ApiResponse state(@PathVariable("id") long id,
                             @RequestBody(required = false) TaskActionRequest req) {
        String action = req == null ? null : req.action();
        String reason = req == null ? null : req.reason();
        return ApiResponse.ok(taskService.act(id, action, reason).toMap());
    }

}
