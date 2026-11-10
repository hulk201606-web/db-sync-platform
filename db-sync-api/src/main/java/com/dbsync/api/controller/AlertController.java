package com.dbsync.api.controller;

import com.dbsync.api.alert.AlertService;
import com.dbsync.api.audit.Audited;
import com.dbsync.api.dto.common.ApiResponse;
import com.dbsync.api.security.CurrentUser;
import com.dbsync.api.security.Permission;
import com.dbsync.api.security.RequiresPermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 告警接口（T45 后端部分：通道 / 规则 / 最近告警 / 手动测试）。
 *
 * <p>// ADDED: 04 文档 T45 要求"告警通道与规则可观测"，但没有列控制器类；
 * 前端控制台按 {@code /api/system/alerts} 约定调用，因此在此落地。
 *
 * <p><b>规则本身是代码定义的</b>（{@link AlertService} 的 {@code RULE_*} 常量，含持续时长与
 * 静默窗口），本期不支持在线改阈值——改阈值必须改配置并重启，避免"点两下把告警关掉"
 * 这种事故；接口只负责"看清楚现在是什么、手动发一条试通道"。
 */
@RestController
@RequestMapping("/api/system/alerts")
public class AlertController {

    private final AlertService alertService;

    /**
     * 构造。
     *
     * @param alertService 告警服务
     */
    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * 告警总览：启用通道 + 规则清单 + 最近告警。
     *
     * @return 概览
     */
    @GetMapping
    @RequiresPermission(Permission.USER_MANAGE)
    public ApiResponse overview() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        List<String> channels = new ArrayList<String>();
        for (AlertService.Channel channel : alertService.channels()) {
            channels.add(channel.name());
        }
        data.put("channels", channels);
        data.put("rules", rules());
        List<Map<String, Object>> recent = new ArrayList<Map<String, Object>>();
        for (AlertService.AlertRecord record : alertService.recent()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("rule", record.getRule());
            row.put("title", record.getTitle());
            row.put("detail", record.getDetail());
            row.put("firedAt", record.getFiredAt() == null ? null
                    : record.getFiredAt().toString());
            row.put("channels", record.getChannels());
            recent.add(row);
        }
        data.put("recent", recent);
        return ApiResponse.ok(data);
    }

    /**
     * 手动发一条测试告警（验证通道是否通）。
     *
     * @return 发送结果
     */
    @PostMapping("/test")
    @RequiresPermission(Permission.USER_MANAGE)
    @Audited(action = "ALERT_TEST", targetType = "ALERT")
    public ApiResponse test() {
        alertService.fire("MANUAL", "人工测试告警",
                "由 " + CurrentUser.actorOrAnonymous() + " 触发，用于验证告警通道",
                Map.of("source", "api"));
        return ApiResponse.ok(Map.of("fired", Boolean.TRUE,
                "channels", alertService.channels().size()));
    }

    /**
     * 按规则编码发测试告警（前端 {@code /alerts/{id}/test} 形态）。
     *
     * @param rule 规则编码（未知编码按人工告警处理）
     * @return 发送结果
     */
    @PostMapping("/{rule}/test")
    @RequiresPermission(Permission.USER_MANAGE)
    @Audited(action = "ALERT_TEST", targetType = "ALERT")
    public ApiResponse testRule(@PathVariable("rule") String rule) {
        alertService.fire(rule, "人工测试告警（" + rule + "）",
                "由 " + CurrentUser.actorOrAnonymous() + " 触发，用于验证告警通道",
                Map.of("source", "api", "rule", rule));
        return ApiResponse.ok(Map.of("fired", Boolean.TRUE, "rule", rule));
    }

    /**
     * 规则清单（编码 + 说明 + 是否可在线改）。
     *
     * @return 规则列表
     */
    private static List<Map<String, Object>> rules() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        rows.add(rule(AlertService.RULE_LAG, "增量延迟 P95 超阈值（持续 60s，静默 30min）"));
        rows.add(rule(AlertService.RULE_FAIL_RATE, "增量失败率超阈值（持续 60s，静默 30min）"));
        rows.add(rule(AlertService.RULE_TASK_FAILED, "任务进入 FAILED（立即，静默 1h）"));
        rows.add(rule(AlertService.RULE_DEAD_LETTER, "未处理死信增长（立即，静默 10min）"));
        rows.add(rule(AlertService.RULE_DATASOURCE_DOWN, "数据源探活 DOWN（持续 60s，静默 30min）"));
        rows.add(rule(AlertService.RULE_LEASE_TAKEOVER, "分片租约被接管（立即，静默 1h）"));
        return rows;
    }

    private static Map<String, Object> rule(String code, String description) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("code", code);
        row.put("description", description);
        row.put("editable", Boolean.FALSE);
        return row;
    }
}
