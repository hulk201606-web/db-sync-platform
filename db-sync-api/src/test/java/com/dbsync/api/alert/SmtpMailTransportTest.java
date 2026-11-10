package com.dbsync.api.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * {@link SmtpMailTransport} 四条分支验收（mock JavaMailSender，不连真实 SMTP）。
 *
 * <p>覆盖：enabled=false 守卫、空收件人/空消息、{@link SimpleMailMessage} 组装
 * （捕获真实传出的消息对象断言 from/to/subject/text）、发送抛 RuntimeException 返回 false。
 */
class SmtpMailTransportTest {

    private static final String FROM = "alerts@example.com";
    private static final MailMessage MESSAGE = new MailMessage(
            List.of("ops@example.com", "dba@example.com"), "[DB-SYNC][RULE_LAG] 延迟告警",
            "规则=RULE_LAG 详情=lag.p95 超限");

    @Test
    @DisplayName("enabled=false：守卫直接返回 false，绝不触碰 sender")
    void disabledSkipsSender() {
        JavaMailSender sender = mock(JavaMailSender.class);
        SmtpMailTransport transport = new SmtpMailTransport(sender, FROM, false);

        assertThat(transport.send(MESSAGE)).isFalse();
        verifyNoInteractions(sender);
    }

    @Test
    @DisplayName("收件人为空或 message 为 null：返回 false，不触碰 sender")
    void emptyRecipientsAreSkipped() {
        JavaMailSender sender = mock(JavaMailSender.class);
        SmtpMailTransport transport = new SmtpMailTransport(sender, FROM, true);

        assertThat(transport.send(new MailMessage(List.of(), "主题", "正文"))).isFalse();
        assertThat(transport.send(null)).isFalse();
        verifyNoInteractions(sender);
    }

    @Test
    @DisplayName("配置齐全：组装 SimpleMailMessage（from/to/subject/text）并下发，返回 true")
    void sendsAssembledSimpleMailMessage() {
        JavaMailSender sender = mock(JavaMailSender.class);
        SmtpMailTransport transport = new SmtpMailTransport(sender, FROM, true);

        assertThat(transport.send(MESSAGE)).isTrue();

        ArgumentCaptor<SimpleMailMessage> captor =
                ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        SimpleMailMessage mail = captor.getValue();
        assertThat(mail.getFrom()).isEqualTo(FROM);
        assertThat(mail.getTo()).containsExactly("ops@example.com", "dba@example.com");
        assertThat(mail.getSubject()).isEqualTo(MESSAGE.subject());
        assertThat(mail.getText()).isEqualTo(MESSAGE.text());
    }

    @Test
    @DisplayName("sender 抛 RuntimeException：吞掉异常返回 false，不向上抛")
    void sendFailureIsSwallowed() {
        JavaMailSender sender = mock(JavaMailSender.class);
        doThrow(new IllegalStateException("smtp connection refused"))
                .when(sender).send(any(SimpleMailMessage.class));
        SmtpMailTransport transport = new SmtpMailTransport(sender, FROM, true);

        assertThatCode(() -> assertThat(transport.send(MESSAGE)).isFalse())
                .doesNotThrowAnyException();
        verify(sender).send(any(SimpleMailMessage.class));
    }
}
