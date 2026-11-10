package com.dbsync.core.port;

import java.util.Map;

/**
 * 告警端口。
 *
 * <p>实现方：api 模块的 {@code AlertService}。LOG / WEBHOOK / EMAIL 三种渠道均已实现：
 * LOG 打日志，WEBHOOK 发 HTTP POST JSON，EMAIL 经 {@code MailTransport} 端口下发
 * （基于 spring-boot-starter-mail 的 SMTP 实现；未配置或发送失败自动降级为不发，
 * 不抛异常、不打断告警链路）。启用哪些渠道由 {@code dbsync.alert.channels} 决定，
 * 默认仅 LOG，可用 {@code DBSYNC_ALERT_CHANNELS} 覆盖。
 */
public interface AlertPort {

    /**
     * 触发告警。
     *
     * @param rule   规则编码（如 {@code GATE_BLOCKED}）
     * @param title  标题
     * @param detail 详情
     * @param tags   标签（用于聚合与路由）
     */
    void fire(String rule, String title, String detail, Map<String, Object> tags);
}
