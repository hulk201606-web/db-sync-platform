package com.dbsync.server.config;

import com.dbsync.api.alert.MailTransport;
import com.dbsync.api.alert.SmtpMailTransport;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * 邮件告警装配（OBS-02）。
 *
 * <p><b>为什么在这里手工建 {@link JavaMailSenderImpl} 而不是依赖 Boot 的
 * {@code MailSenderAutoConfiguration}</b>：Boot 的自动装配以 {@code spring.mail.*}
 * 为准，而本平台的配置统一在 {@code dbsync.*} 下（见 {@link DbSyncProperties}）。
 * 手工装配只有一个真源，避免"两处配置、谁生效说不清"。
 *
 * <p><b>配置不全不报错</b>：返回一个 {@code enabled=false} 的
 * {@link SmtpMailTransport}，它的 {@code send} 直接返回 false。
 * 这样 {@code AlertService} 永远拿到非 null 的端口，分支更少；
 * "发不发"由配置决定，"配置错了"只留一行启动日志。
 */
@Configuration
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    /**
     * 邮件发送端口（生产实现：SMTP）。
     *
     * @param properties 平台配置（{@code dbsync.alert.mail.*}）
     * @return 端口实现（永不为 null）
     */
    @Bean
    public MailTransport mailTransport(DbSyncProperties properties) {
        DbSyncProperties.Alert.Mail mail = properties.getAlert().getMail();
        if (mail == null || !mail.isConfigured()) {
            log.info("邮件告警未启用或配置不全（enabled={} host={} from={} recipients={}），"
                            + "邮件渠道将静默跳过", Boolean.valueOf(mail != null && mail.isEnabled()),
                    mail == null ? null : mail.getHost(), mail == null ? null : mail.getFrom(),
                    mail == null ? null : mail.getRecipients());
            return new SmtpMailTransport(null, null, false);
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(mail.getHost());
        sender.setPort(mail.getPort());
        sender.setDefaultEncoding("UTF-8");
        if (mail.getUsername() != null && !mail.getUsername().isBlank()) {
            sender.setUsername(mail.getUsername());
            sender.setPassword(mail.getPassword());
        }
        sender.setJavaMailProperties(javaMailProperties(mail));
        log.info("邮件告警已启用 host={} port={} starttls={} ssl={} recipients={}", mail.getHost(),
                Integer.valueOf(mail.getPort()), Boolean.valueOf(mail.isStarttls()),
                Boolean.valueOf(mail.isSsl()), mail.getRecipients());
        return new SmtpMailTransport(sender, mail.getFrom(), true);
    }

    /**
     * 组装 jakarta.mail 的 SMTP 属性（超时是三处独立的：连、读、写）。
     *
     * @param mail 邮件配置
     * @return 属性表
     */
    private static Properties javaMailProperties(DbSyncProperties.Alert.Mail mail) {
        int timeout = mail.getConnectTimeoutMs() <= 0 ? 5000 : mail.getConnectTimeoutMs();
        Properties props = new Properties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth",
                String.valueOf(mail.getUsername() != null && !mail.getUsername().isBlank()));
        props.put("mail.smtp.starttls.enable", String.valueOf(mail.isStarttls()));
        props.put("mail.smtp.ssl.enable", String.valueOf(mail.isSsl()));
        props.put("mail.smtp.connectiontimeout", String.valueOf(timeout));
        props.put("mail.smtp.timeout", String.valueOf(timeout));
        props.put("mail.smtp.writetimeout", String.valueOf(timeout));
        return props;
    }
}
