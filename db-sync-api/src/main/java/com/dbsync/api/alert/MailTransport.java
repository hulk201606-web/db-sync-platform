package com.dbsync.api.alert;

/**
 * 邮件发送端口（OBS-02）。
 *
 * <p><b>为什么抽出端口而不是直接调 {@code JavaMailSender}</b>：
 * <ol>
 *   <li>单测不能连真实 SMTP（CI 无内网 SMTP，也不该在单测里发信），
 *       有了端口就能用假实现断言"发给谁、主题是什么、发了几次"；</li>
 *   <li>SMTP 只是众多投递方式之一，端口让将来接"企业微信/短信"时
 *       不必改 {@link AlertService}（它只认识"发一条消息"）。</li>
 * </ol>
 *
 * <p><b>契约</b>：实现<b>不得</b>抛异常——发送失败返回 {@code false} 并自行记日志。
 * 调用方（告警链路）只把它当成"尽力而为"的下发动作：
 * 一个邮件故障绝不能把整条告警链路打断（日志/Webhook 仍要正常下发）。
 * {@link AlertService} 仍会再兜一层 try/catch，因为第三方实现未必守约。
 */
public interface MailTransport {

    /**
     * 发送一封邮件。
     *
     * @param message 邮件内容（收件人/主题/正文）
     * @return true 表示已交给 SMTP 服务器；false 表示未配置或发送失败
     */
    boolean send(MailMessage message);
}
