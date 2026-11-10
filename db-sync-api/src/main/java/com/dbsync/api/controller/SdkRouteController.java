package com.dbsync.api.controller;

import com.dbsync.api.dto.common.ApiResponse;
import com.dbsync.api.dto.sdk.RouteAckRequest;
import com.dbsync.api.security.Permission;
import com.dbsync.api.security.RequiresPermission;
import com.dbsync.api.service.SdkRouteService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 业务侧 SDK 专用通道（PRD CUT-01：每次变更广播给 SDK，≤3 秒生效）。
 *
 * <p><b>鉴权方式</b>：业务侧 SDK 不带人账号，而是由运维在平台建一个<b>只读服务账号</b>
 * （角色 VIEWER 即可，权限点 {@link Permission#TASK_VIEW}），把该账号的令牌
 * 配到 SDK 的 {@code token}（{@code X-DBSync-Token} 头）。理由：
 * <ul>
 *   <li>复用现有 RBAC，不新开一套"SDK 密钥"体系；</li>
 *   <li>令牌可随时吊销（改口令即失效），比硬编码密钥可控；</li>
 *   <li>只给 {@code TASK_VIEW} 保证 SDK 即便被攻破也改不了任何状态。</li>
 * </ul>
 *
 * <p><b>为什么快照端点不返回 404</b>：SDK 的调用发生在业务请求路径的缓存刷新里，
 * "任务还没有切流计划"是完全正常的状态（例如刚建任务）。此时返回全源兜底的快照
 * （{@code routeVersion=0, state=NONE, readFrom=SOURCE, writeTo=SOURCE}），
 * 让业务侧永远有一个可执行的决策，而不是把"平台没数据"变成业务侧的异常。
 */
@RestController
@RequestMapping("/api/sdk")
public class SdkRouteController {

    private final SdkRouteService sdkRouteService;

    /**
     * 构造。
     *
     * @param sdkRouteService 路由服务
     */
    public SdkRouteController(SdkRouteService sdkRouteService) {
        this.sdkRouteService = sdkRouteService;
    }

    /**
     * 路由快照（SDK 轮询入口）。
     *
     * @param taskId 任务 ID
     * @return 快照（无计划时为全源兜底）
     */
    @GetMapping("/route-snapshot")
    @RequiresPermission(Permission.TASK_VIEW)
    public ApiResponse snapshot(@RequestParam("taskId") long taskId) {
        return ApiResponse.ok(sdkRouteService.snapshot(taskId).toMap());
    }

    /**
     * 路由确认（SDK 报告"我已生效到哪一版"）。
     *
     * @param req 请求
     * @return 结果（{@code applied=false} 表示未采纳：无计划或版本不新）
     */
    @PostMapping("/route-ack")
    @RequiresPermission(Permission.TASK_VIEW)
    public ApiResponse ack(@Valid @RequestBody RouteAckRequest req) {
        return ApiResponse.ok(sdkRouteService.ack(req).toMap());
    }
}
