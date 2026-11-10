package com.dbsync.api.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 生产用 SMTP 邮件发送实现（基于 Spring 官方 {@code JavaMailSender}）。
 *
 * <p>三条纪律：
 * <ol>
 *   <li><b>未启用/未配置直接返回 false</b>，连连接都不建——配置缺失是常态（默认
 *       {@code enabled=false}），不能因为"没配邮件"就在每次告警时打一条异常栈；</li>
 *   <li><b>发送失败只记日志</b>（{@code log.error}），返回 false，异常绝不上抛：
 *       邮件服务器宕机不应让告警链路（日志/Webhook）跟着失败；</li>
 *   <li><b>连接/读超时由外部配置注入</b>（{@code spring.mail.properties.mail.smtp.*}），
 *       本类不硬编码超时值——否则 SMTP 卡住时会把告警线程一起拖死。</li>
 * </ol>
 */
public class SmtpMailTransport implements MailTransport {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailTransport.class);

    private final JavaMailSender sender;
    private final String from;
    private final boolean enabled;

    /**
     * 构造。
     *
     * @param sender  邮件发送器（配置齐全时非 null）
     * @param from    发件人（SMTP 通常要求与认证账号一致）
     * @param enabled 是否启用（{@code dbsync.alert.mail.enabled}）
     */
    public SmtpMailTransport(JavaMailSender sender, String from, boolean enabled) {
        this.sender = sender;
        this.from = from;
        this.enabled = enabled;
    }

    @Override
    public boolean send(MailMessage message) {
        if (!enabled || sender == null || from == null || from.isBlank()) {
            log.debug("SMTP 未启用或未配置，跳过邮件下发 subject={}",
                    message == null ? null : message.subject());
            return false;
        }
        if (message == null || message.to().isEmpty()) {
            log.warn("邮件收件人为空，跳过下发 subject={}",
                    message == null ? null : message.subject());
            return false;
        }
        try {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(from);
            mail.setTo(message.to().toArray(new String[0]));
            mail.setSubject(message.subject());
            mail.setText(message.text());
            sender.send(mail);
            log.info("邮件告警已下发 recipients={} subject={}", message.to(), message.subject());
            return true;
        } catch (RuntimeException e) {
            // 连接不上/认证失败/超时都收敛到这里：返回 false，绝不打断告警链路
            log.error("邮件告警下发失败 recipients={} subject={} err={}", message.to(),
                    message.subject(), e.toString());
            return false;
        }
    }
}
