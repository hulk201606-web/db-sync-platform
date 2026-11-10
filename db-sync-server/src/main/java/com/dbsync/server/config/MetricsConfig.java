package com.dbsync.server.config;

import com.dbsync.api.alert.AlertService;
import com.dbsync.api.alert.MailTransport;
import com.dbsync.api.metrics.AlertSourceAdapter;
import com.dbsync.api.metrics.SyncMetrics;
import com.dbsync.core.port.IncrementalMetricsPort;
import com.dbsync.metadata.dao.DataSourceDao;
import com.dbsync.metadata.dao.DeadLetterDao;
import com.dbsync.metadata.dao.ShardDao;
import com.dbsync.metadata.dao.SyncTaskDao;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 指标与告警装配（架构 §11）。
 *
 * <p>指标口径以 {@link SyncMetrics} 为唯一定义点（15 个 {@code dbsync.*} 指标），
 * 告警规则以 {@link AlertService} 为唯一定义点（6 条规则 + 持续时长 + 静默窗口）。
 */
@Configuration
public class MetricsConfig {

    private static final Logger log = LoggerFactory.getLogger(MetricsConfig.class);

    /**
     * 业务指标登记器。
     *
     * @param registry Micrometer 注册表（Prometheus 或 simple）
     * @return 指标
     */
    @Bean
    public SyncMetrics syncMetrics(MeterRegistry registry) {
        return new SyncMetrics(registry);
    }

    /**
     * 时钟（可注入以方便测试，生产用 UTC）。
     *
     * @return 时钟
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * 告警取数源。
     *
     * @param syncTaskDao           任务 DAO
     * @param shardDao              分片 DAO
     * @param dataSourceDao         数据源 DAO
     * @param deadLetterDao         死信 DAO
     * @param incrementalMetricsPort 增量指标端口
     * @param properties            配置
     * @return 取数源
     */
    @Bean
    public AlertService.AlertSource alertSource(SyncTaskDao syncTaskDao, ShardDao shardDao,
                                                DataSourceDao dataSourceDao,
                                                DeadLetterDao deadLetterDao,
                                                IncrementalMetricsPort incrementalMetricsPort,
                                                DbSyncProperties properties) {
        return new AlertSourceAdapter(syncTaskDao, shardDao, dataSourceDao, deadLetterDao,
                incrementalMetricsPort, properties.getAlert().getLagThresholdMs(),
                properties.getAlert().getFailRateThreshold());
    }

    /**
     * 告警服务。
     *
     * @param source        取数源
     * @param clock         时钟
     * @param properties    配置
     * @param mailTransport 邮件端口（配置不全时是"禁用实现"，send 恒返回 false）
     * @return 服务
     */
    @Bean
    public AlertService alertService(AlertService.AlertSource source, Clock clock,
                                     DbSyncProperties properties, MailTransport mailTransport) {
        Set<AlertService.Channel> channels = EnumSet.noneOf(AlertService.Channel.class);
        List<String> configured = properties.getAlert().getChannels();
        if (configured != null) {
            for (String raw : configured) {
                if (raw == null) {
                    continue;
                }
                // 显式按逗号拆分 + 去空白，不依赖 relaxed binding 对标量/列表的猜测行为：
                // dbsync.alert.channels 既可能是 YAML 列表，也可能是 "LOG,WEBHOOK,EMAIL" 标量
                // （环境变量覆盖场景），两种形态都要解析成同一组渠道枚举。
                for (String part : raw.split(",")) {
                    String name = part.trim();
                    if (name.isEmpty()) {
                        continue;
                    }
                    try {
                        channels.add(AlertService.Channel.valueOf(name.toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException e) {
                        log.warn("忽略未知告警渠道：{}", name);
                    }
                }
            }
        }
        if (channels.isEmpty()) {
            channels.add(AlertService.Channel.LOG);
        }
        DbSyncProperties.Alert.Mail mail = properties.getAlert().getMail();
        boolean mailConfigured = mail != null && mail.isConfigured();
        String webhookUrl = properties.getAlert().getWebhookUrl();
        boolean webhookConfigured = webhookUrl != null && !webhookUrl.isBlank();
        AlertService.MailOptions mailOptions = new AlertService.MailOptions(
                mailConfigured, mail == null ? List.of() : mail.getRecipients());

        // 启动可见性的 INFO 统一放在 DbSyncProperties.validate() 那一条（避免两套渠道日志）；
        // 这里只保留"启用但配置不全"的 WARN——判断依赖解析后的 channels 集合，必须在装配处做。
        if (channels.contains(AlertService.Channel.EMAIL) && !mailConfigured) {
            // 邮件渠道被启用但邮件配置不全：启动时就提示，而不是等到第一次告警才"静默没发出去"
            log.warn("告警渠道包含 EMAIL，但 dbsync.alert.mail 未启用或配置不全，"
                    + "邮件告警将被跳过（其它渠道不受影响）");
        }
        if (channels.contains(AlertService.Channel.WEBHOOK) && !webhookConfigured) {
            // WEBHOOK 启用却没配 URL：同样在启动时就提示，避免"以为配了其实一条都发不出去"
            log.warn("告警渠道包含 WEBHOOK，但 dbsync.alert.webhook-url 未配置，"
                    + "Webhook 告警将被跳过（其它渠道不受影响）");
        }
        return new AlertService(source, clock, channels, webhookUrl,
                Duration.ofSeconds(5L), mailTransport, mailOptions);
    }
}
