package com.dbsync.api.alert;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OBS-02 邮件告警验收。
 *
 * <p><b>不连真实 SMTP</b>：单测环境没有邮件服务器，也不该在单测里发信。
 * 所有断言都打在 {@link MailTransport} 的假实现上——"发给谁、主题是什么、发了几次"
 * 这三件事才是邮件渠道的正确性所在；真连服务器属于部署验证。
 *
 * <p>四条硬要求（对应验收 ①~④）：
 * <ol>
 *   <li>未配置 → 返回 false，<b>不抛</b>；</li>
 *   <li>配置齐全 → 收件人/主题正确（主题必须含规则编码）；</li>
 *   <li>发送抛异常 → 仍返回 false，<b>不向上抛</b>（绝不打断告警链路）；</li>
 *   <li>静默窗口内第二次告警不再发信（与 Webhook 共用同一套去重逻辑）。</li>
 * </ol>
 */
class AlertMailTest {

    /** 记录下发了什么的假端口（{@code fail=true} 时抛异常，模拟 SMTP 故障）。 */
    static final class FakeTransport implements MailTransport {

        private final List<MailMessage> sent = new ArrayList<>();
        private boolean fail;

        @Override
        public boolean send(MailMessage message) {
            if (fail) {
                throw new IllegalStateException("smtp connection refused");
            }
            sent.add(message);
            return true;
        }
    }

    @Test
    @DisplayName("① 未配置邮件：sendEmail 返回 false 且不抛异常")
    void should_returnFalse_When_mailNotConfigured() {
        AlertService service = new AlertService(new AlertTest.FakeSource());

        assertFalse(service.sendEmail(AlertService.RULE_LAG, "标题", "详情", Map.of()));

        // 渠道开着但邮件配置不全（enabled=false）：同样只返回 false
        AlertService withEmailChannel = new AlertService(new AlertTest.FakeSource(),
                new AlertTest.MutableClock(), EnumSet.of(AlertService.Channel.LOG,
                AlertService.Channel.EMAIL), null, Duration.ofSeconds(1L), new FakeTransport(),
                AlertService.MailOptions.DISABLED);
        assertFalse(withEmailChannel.sendEmail(AlertService.RULE_LAG, "标题", "详情", Map.of()));
    }

    @Test
    @DisplayName("② 配置齐全：假端口收到正确收件人与含规则编码的主题")
    void should_sendWithRecipientsAndSubject_When_configured() {
        FakeTransport transport = new FakeTransport();
        AlertTest.FakeSource source = new AlertTest.FakeSource();
        source.taskNames.put(Long.valueOf(1001L), "订单库迁移");
        AlertService service = service(transport, new AlertTest.MutableClock(), source);

        boolean sent = service.sendEmail(AlertService.RULE_TASK_FAILED, "任务失败",
                "FAILED 任务数=1 ids=[1001]",
                Map.of("taskIds", List.of(1001L), "taskId", Long.valueOf(1001L)));

        assertTrue(sent);
        assertEquals(1, transport.sent.size());
        MailMessage message = transport.sent.get(0);
        assertEquals(List.of("ops@example.com", "dba@example.com"), message.to());
        assertTrue(message.subject().contains(AlertService.RULE_TASK_FAILED),
                "主题必须含规则编码便于邮件侧分派：" + message.subject());
        assertTrue(message.text().contains(AlertService.RULE_TASK_FAILED), message.text());
        assertTrue(message.text().contains("任务失败"), message.text());
        // 任务名（由 AlertSource 反查）+ 跳转链接 + 触发时间，缺一个值班就得自己翻库
        assertTrue(message.text().contains("订单库迁移"), message.text());
        assertTrue(message.text().contains("ID 1001"), message.text());
        assertTrue(message.text().contains("跳转链接"), message.text());
        assertTrue(message.text().contains("/tasks/1001"), message.text());
        assertTrue(message.text().contains("触发时间"), message.text());
    }

    @Test
    @DisplayName("③ 发送抛异常：sendEmail 仍返回 false 且不向上抛")
    void should_returnFalse_When_transportThrows() {
        MailTransport transport = mock(MailTransport.class);
        when(transport.send(any(MailMessage.class)))
                .thenThrow(new IllegalStateException("smtp connection refused"));
        AlertService service = new AlertService(new AlertTest.FakeSource(),
                new AlertTest.MutableClock(),
                EnumSet.of(AlertService.Channel.LOG, AlertService.Channel.EMAIL), null,
                Duration.ofSeconds(1L), transport,
                new AlertService.MailOptions(true,
                        List.of("ops@example.com", "dba@example.com")));

        assertDoesNotThrow(() -> assertFalse(
                service.sendEmail(AlertService.RULE_TASK_FAILED, "任务失败", "详情", Map.of()),
                "邮件故障必须被吞掉，不能抛给告警调用方"),
                "transport 抛异常时 sendEmail 不得把异常抛给调用方");
        // 确实尝试下发过一次，且异常被 sendEmail 吞掉（而不是根本没走到 transport）
        verify(transport, times(1)).send(any(MailMessage.class));
    }

    @Test
    @DisplayName("④ 静默期内第二次告警不再发信（与 Webhook 共用去重逻辑，transport 只被调一次）")
    void should_sendEmailOnlyOnce_WithinSilenceWindow() {
        FakeTransport transport = new FakeTransport();
        AlertTest.MutableClock clock = new AlertTest.MutableClock();
        AlertTest.FakeSource source = new AlertTest.FakeSource();
        AlertService service = service(transport, clock, source);

        source.failedTasks = List.of(1001L);
        assertEquals(1, service.evaluate().size(), "任务失败立即告警（持续时长 0）");
        assertEquals(1, transport.sent.size());

        clock.advance(Duration.ofMinutes(5L));
        assertTrue(service.evaluate().isEmpty(), "静默期内不重复告警");
        assertEquals(1, transport.sent.size(), "静默期内不得重复发信");
        assertTrue(service.isSilenced(AlertService.RULE_TASK_FAILED));

        // 静默窗口（1h）过后再发一次，证明不是"永久只发一次"
        clock.advance(Duration.ofMinutes(56L));
        assertEquals(1, service.evaluate().size());
        assertEquals(2, transport.sent.size());
    }

    @Test
    @DisplayName("邮件与日志/Webhook 同批下发：email 失败不影响历史记录与其它渠道")
    void should_keepAlertRecord_When_emailFails() {
        FakeTransport transport = new FakeTransport();
        transport.fail = true;
        AlertTest.FakeSource source = new AlertTest.FakeSource();
        AlertService service = service(transport, new AlertTest.MutableClock(), source);

        source.failedTasks = List.of(1001L, 1002L);
        List<AlertService.AlertRecord> fired = service.evaluate();

        assertEquals(1, fired.size(), "邮件失败不得吞掉告警本身");
        assertTrue(fired.get(0).getChannels().contains(AlertService.Channel.EMAIL),
                "已下发渠道应包含 EMAIL：" + fired.get(0).getChannels());
        assertEquals(1, service.recent().size());
    }

    /**
     * 组装"邮件已配置"的服务。
     *
     * @param transport 假端口
     * @param clock     时钟
     * @return 服务
     */
    private static AlertService service(FakeTransport transport, AlertTest.MutableClock clock) {
        return service(transport, clock, new AlertTest.FakeSource());
    }

    /**
     * 组装"邮件已配置"的服务（指定数据源）。
     *
     * @param transport 假端口
     * @param clock     时钟
     * @param source    数据源
     * @return 服务
     */
    private static AlertService service(FakeTransport transport, AlertTest.MutableClock clock,
                                        AlertTest.FakeSource source) {
        return new AlertService(source, clock,
                EnumSet.of(AlertService.Channel.LOG, AlertService.Channel.EMAIL), null,
                Duration.ofSeconds(1L), transport,
                new AlertService.MailOptions(true,
                        List.of("ops@example.com", "dba@example.com")));
    }
}
