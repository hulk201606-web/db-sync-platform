package com.dbsync.server.config;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 平台运行参数（{@code dbsync.*}）。
 *
 * <p>设计取舍：
 * <ul>
 *   <li><b>主密钥只能来自环境变量</b>（架构 §12.1）：{@code DBSYNC_MASTER_KEY} 缺失时
 *       <b>启动即失败</b>，绝不"用默认密钥兜底跑起来"——否则等于把数据源口令明文存库；</li>
 *   <li>阈值（延迟/失败率）放配置里，便于按环境调，但<b>判定逻辑仍在 GateEvaluator 一处</b>；</li>
 *   <li>所有 {@code Duration} 用秒/毫秒整数表达，避免 Spring 绑定歧义。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "dbsync")
public class DbSyncProperties {

    private static final Logger log = LoggerFactory.getLogger(DbSyncProperties.class);

    /** 主密钥（AES-GCM 根密钥，32 字节以上；来源：环境变量 DBSYNC_MASTER_KEY）。 */
    private String masterKey;
    /** 上一版主密钥（轮换期双读，可空）。 */
    private String previousMasterKey;
    /** 当前密钥版本（≥1）。 */
    private short keyVersion = 1;

    /** 实例标识相关配置。 */
    private Instance instance = new Instance();
    /** 数据源连接池默认值。 */
    private Pool pool = new Pool();
    /** 分片租约。 */
    private Lease lease = new Lease();
    /** 认领策略（SKIP_LOCKED / NOWAIT_PROBE / OPTIMISTIC_CAS）。 */
    private String claimStrategy = "SKIP_LOCKED";
    /** 比对/核数报告落盘目录。 */
    private String reportDir = "reports";
    /** 认证。 */
    private Auth auth = new Auth();
    /** 告警。 */
    private Alert alert = new Alert();
    /** 定时作业周期。 */
    private Jobs jobs = new Jobs();
    /** 增量采集配置。 */
    private Cdc cdc = new Cdc();

    /**
     * 启动自检：缺主密钥直接失败（宁可起不来，不可带病运行）。
     */
    @PostConstruct
    void validate() {
        if (masterKey == null || masterKey.isBlank()) {
            throw new IllegalStateException(
                    "缺少主密钥：请设置环境变量 DBSYNC_MASTER_KEY（≥32 字符），"
                            + "否则数据源口令无法安全落库");
        }
        if (masterKey.length() < 32) {
            throw new IllegalStateException("DBSYNC_MASTER_KEY 太短（需 ≥32 字符，与 application.yml / 文档一致）");
        }
        if (keyVersion < 1) {
            throw new IllegalStateException("dbsync.key-version 必须 ≥1");
        }
        try {
            ClaimStrategyParser.parse(claimStrategy);
        } catch (RuntimeException e) {
            throw new IllegalStateException("dbsync.claim-strategy 非法：" + claimStrategy, e);
        }
        // 启动可见性集中在这一条：生效渠道 + mail / webhook 是否 configured，
        // 让"默认仅 LOG、外部渠道全静默"在启动日志里一眼可见（不必另起一套渠道日志）。
        DbSyncProperties.Alert.Mail mailCfg = alert.getMail();
        boolean mailConfigured = mailCfg != null && mailCfg.isConfigured();
        String webhookUrl = alert.getWebhookUrl();
        boolean webhookConfigured = webhookUrl != null && !webhookUrl.isBlank();
        log.info("dbsync 配置就绪 keyVersion={} claimStrategy={} reportDir={} 生效渠道={} "
                        + "mailConfigured={} webhookConfigured={}"
                        + "（渠道可用 DBSYNC_ALERT_CHANNELS 覆盖，逗号分隔）",
                Short.valueOf(keyVersion), claimStrategy, reportDir, alert.getChannels(),
                Boolean.valueOf(mailConfigured), Boolean.valueOf(webhookConfigured));
    }

    /** @return 租约时长 */
    public Duration leaseDuration() {
        return Duration.ofSeconds(lease.getDurationSeconds());
    }

    /** @return 续租间隔 */
    public Duration leaseHeartbeat() {
        return Duration.ofMillis(lease.getHeartbeatMillis());
    }

    /** @return 令牌有效期 */
    public Duration tokenTtl() {
        return Duration.ofMinutes(auth.getTokenTtlMinutes());
    }

    /**
     * 会话空闲超时（SEC-05）。
     *
     * @return 空闲超时；{@code Duration.ZERO} 表示禁用空闲失效（只保留 TTL）
     */
    public Duration tokenIdleTimeout() {
        long minutes = auth.getIdleTimeoutMinutes();
        return minutes <= 0L ? Duration.ZERO : Duration.ofMinutes(minutes);
    }

    /** @return 主密钥字节 */
    public byte[] masterKeyBytes() {
        return masterKey == null ? new byte[0] : masterKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** @return 上一版密钥字节（无则 null） */
    public byte[] previousMasterKeyBytes() {
        return previousMasterKey == null || previousMasterKey.isBlank() ? null
                : previousMasterKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** getter/setter 由 Spring Boot 绑定使用（手写，不用 Lombok）。 */
    public String getMasterKey() {
        return masterKey;
    }

    /**
     * @param masterKey 主密钥
     */
    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey;
    }

    /** @return 上一版主密钥 */
    public String getPreviousMasterKey() {
        return previousMasterKey;
    }

    /**
     * @param previousMasterKey 上一版主密钥
     */
    public void setPreviousMasterKey(String previousMasterKey) {
        this.previousMasterKey = previousMasterKey;
    }

    /** @return 密钥版本 */
    public short getKeyVersion() {
        return keyVersion;
    }

    /**
     * @param keyVersion 密钥版本
     */
    public void setKeyVersion(short keyVersion) {
        this.keyVersion = keyVersion;
    }

    /** @return 实例配置 */
    public Instance getInstance() {
        return instance;
    }

    /**
     * @param instance 实例配置
     */
    public void setInstance(Instance instance) {
        this.instance = instance;
    }

    /** @return 连接池默认值 */
    public Pool getPool() {
        return pool;
    }

    /**
     * @param pool 连接池默认值
     */
    public void setPool(Pool pool) {
        this.pool = pool;
    }

    /** @return 租约配置 */
    public Lease getLease() {
        return lease;
    }

    /**
     * @param lease 租约配置
     */
    public void setLease(Lease lease) {
        this.lease = lease;
    }

    /** @return 认领策略 */
    public String getClaimStrategy() {
        return claimStrategy;
    }

    /**
     * @param claimStrategy 认领策略
     */
    public void setClaimStrategy(String claimStrategy) {
        this.claimStrategy = claimStrategy;
    }

    /** @return 报告目录 */
    public String getReportDir() {
        return reportDir;
    }

    /**
     * @param reportDir 报告目录
     */
    public void setReportDir(String reportDir) {
        this.reportDir = reportDir;
    }

    /** @return 认证配置 */
    public Auth getAuth() {
        return auth;
    }

    /**
     * @param auth 认证配置
     */
    public void setAuth(Auth auth) {
        this.auth = auth;
    }

    /** @return 告警配置 */
    public Alert getAlert() {
        return alert;
    }

    /**
     * @param alert 告警配置
     */
    public void setAlert(Alert alert) {
        this.alert = alert;
    }

    /** @return 作业周期 */
    public Jobs getJobs() {
        return jobs;
    }

    /**
     * @param jobs 作业周期
     */
    public void setJobs(Jobs jobs) {
        this.jobs = jobs;
    }

    /** @return 增量采集配置 */
    public Cdc getCdc() {
        return cdc;
    }

    /**
     * @param cdc 增量采集配置
     */
    public void setCdc(Cdc cdc) {
        this.cdc = cdc;
    }

    /** 实例配置。 */
    public static class Instance {

        /** 显式主机名（空则自动探测）。 */
        private String host = "";
        /** 服务端口（用于拼 instanceId）。 */
        private int port = 8080;

        /** @return 主机名 */
        public String getHost() {
            return host;
        }

        /**
         * @param host 主机名
         */
        public void setHost(String host) {
            this.host = host;
        }

        /** @return 端口 */
        public int getPort() {
            return port;
        }

        /**
         * @param port 端口
         */
        public void setPort(int port) {
            this.port = port;
        }
    }

    /** 连接池默认值。 */
    public static class Pool {

        /** 池上限（架构 §13.1：不超过目标库 max_connections 的 0.6）。 */
        private int maxSize = 8;
        /** 最小空闲。 */
        private int minIdle = 1;
        /** 流式读 fetchSize。 */
        private int fetchSize = 1000;

        /** @return 池上限 */
        public int getMaxSize() {
            return maxSize;
        }

        /**
         * @param maxSize 池上限
         */
        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        /** @return 最小空闲 */
        public int getMinIdle() {
            return minIdle;
        }

        /**
         * @param minIdle 最小空闲
         */
        public void setMinIdle(int minIdle) {
            this.minIdle = minIdle;
        }

        /** @return fetchSize */
        public int getFetchSize() {
            return fetchSize;
        }

        /**
         * @param fetchSize fetchSize
         */
        public void setFetchSize(int fetchSize) {
            this.fetchSize = fetchSize;
        }
    }

    /** 分片租约配置。 */
    public static class Lease {

        /** 租约时长（秒）。 */
        private long durationSeconds = 30L;
        /** 续租间隔（毫秒）。 */
        private long heartbeatMillis = 10_000L;

        /** @return 租约时长 */
        public long getDurationSeconds() {
            return durationSeconds;
        }

        /**
         * @param durationSeconds 租约时长
         */
        public void setDurationSeconds(long durationSeconds) {
            this.durationSeconds = durationSeconds;
        }

        /** @return 续租间隔 */
        public long getHeartbeatMillis() {
            return heartbeatMillis;
        }

        /**
         * @param heartbeatMillis 续租间隔
         */
        public void setHeartbeatMillis(long heartbeatMillis) {
            this.heartbeatMillis = heartbeatMillis;
        }
    }

    /** 认证配置。 */
    public static class Auth {

        /** 令牌有效期（分钟，默认 8 小时）。 */
        private long tokenTtlMinutes = 480L;

        /**
         * 会话空闲超时（分钟，默认 30；{@code 0} 表示禁用空闲失效）。
         *
         * <p>与 {@link #tokenTtlMinutes} 是<b>两条独立规则</b>：TTL 是"签发后最长活多久"，
         * 空闲超时是"多久不动就失效"。两者取"先到者"生效，互不覆盖。
         */
        private long idleTimeoutMinutes = 30L;

        /** @return TTL（分钟） */
        public long getTokenTtlMinutes() {
            return tokenTtlMinutes;
        }

        /**
         * @param tokenTtlMinutes TTL（分钟）
         */
        public void setTokenTtlMinutes(long tokenTtlMinutes) {
            this.tokenTtlMinutes = tokenTtlMinutes;
        }

        /** @return 空闲超时（分钟；0 表示禁用） */
        public long getIdleTimeoutMinutes() {
            return idleTimeoutMinutes;
        }

        /**
         * @param idleTimeoutMinutes 空闲超时（分钟；0 表示禁用）
         */
        public void setIdleTimeoutMinutes(long idleTimeoutMinutes) {
            this.idleTimeoutMinutes = idleTimeoutMinutes;
        }
    }

    /** 告警配置。 */
    public static class Alert {

        /** 启用渠道（LOG/WEBHOOK/EMAIL）。 */
        private List<String> channels = new ArrayList<>(List.of("LOG"));
        /** Webhook 地址（启用 WEBHOOK 时必填）。 */
        private String webhookUrl;
        /** 延迟告警阈值（毫秒）。 */
        private long lagThresholdMs = 60_000L;
        /** 失败率告警阈值（0~1）。 */
        private double failRateThreshold = 0.001d;
        /** 邮件渠道配置。 */
        private Mail mail = new Mail();

        /** @return 渠道 */
        public List<String> getChannels() {
            return channels;
        }

        /**
         * @param channels 渠道
         */
        public void setChannels(List<String> channels) {
            this.channels = channels;
        }

        /** @return Webhook 地址 */
        public String getWebhookUrl() {
            return webhookUrl;
        }

        /**
         * @param webhookUrl Webhook 地址
         */
        public void setWebhookUrl(String webhookUrl) {
            this.webhookUrl = webhookUrl;
        }

        /** @return 延迟阈值 */
        public long getLagThresholdMs() {
            return lagThresholdMs;
        }

        /**
         * @param lagThresholdMs 延迟阈值
         */
        public void setLagThresholdMs(long lagThresholdMs) {
            this.lagThresholdMs = lagThresholdMs;
        }

        /** @return 失败率阈值 */
        public double getFailRateThreshold() {
            return failRateThreshold;
        }

        /**
         * @param failRateThreshold 失败率阈值
         */
        public void setFailRateThreshold(double failRateThreshold) {
            this.failRateThreshold = failRateThreshold;
        }

        /** @return 邮件配置 */
        public Mail getMail() {
            return mail;
        }

        /**
         * @param mail 邮件配置
         */
        public void setMail(Mail mail) {
            this.mail = mail;
        }

        /**
         * 邮件告警配置（{@code dbsync.alert.mail.*}，OBS-02）。
         *
         * <p><b>默认关闭</b>：没有 SMTP 的环境（开发、CI、单机演练）必须能直接起服务，
         * 因此 {@code enabled=false} 是默认值；打开后配置不全也只降级为"不发邮件 + 一次 WARN"，
         * 不会影响服务启动与其它告警渠道。
         *
         * <p>全部字段都可用环境变量覆盖（见 {@code application.yml}）。
         */
        public static class Mail {

            /** 是否启用邮件渠道。 */
            private boolean enabled = false;
            /** SMTP 主机。 */
            private String host;
            /** SMTP 端口（默认 25；SSL 常用 465，STARTTLS 常用 587）。 */
            private int port = 25;
            /** SMTP 用户名（可空：内网免认证中继）。 */
            private String username;
            /** SMTP 口令（建议由环境变量注入，不要写进仓库）。 */
            private String password;
            /** 发件人地址（多数 SMTP 要求与认证账号一致）。 */
            private String from;
            /** 是否启用 STARTTLS（587 端口常见）。 */
            private boolean starttls = false;
            /** 是否启用 SSL（465 端口常见）。 */
            private boolean ssl = false;
            /** 连接/读超时（毫秒）：避免 SMTP 卡住时拖死告警线程。 */
            private int connectTimeoutMs = 5000;
            /** 收件人列表。 */
            private List<String> recipients = new ArrayList<>();

            /**
             * 配置是否齐全（齐了才建 SMTP 客户端，避免半成品配置引发一堆连接异常）。
             *
             * @return true 表示可以发信
             */
            public boolean isConfigured() {
                return enabled && host != null && !host.isBlank() && from != null
                        && !from.isBlank() && recipients != null && !recipients.isEmpty();
            }

            /** @return 是否启用 */
            public boolean isEnabled() {
                return enabled;
            }

            /**
             * @param enabled 是否启用
             */
            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            /** @return SMTP 主机 */
            public String getHost() {
                return host;
            }

            /**
             * @param host SMTP 主机
             */
            public void setHost(String host) {
                this.host = host;
            }

            /** @return SMTP 端口 */
            public int getPort() {
                return port;
            }

            /**
             * @param port SMTP 端口
             */
            public void setPort(int port) {
                this.port = port;
            }

            /** @return 用户名 */
            public String getUsername() {
                return username;
            }

            /**
             * @param username 用户名
             */
            public void setUsername(String username) {
                this.username = username;
            }

            /** @return 口令 */
            public String getPassword() {
                return password;
            }

            /**
             * @param password 口令
             */
            public void setPassword(String password) {
                this.password = password;
            }

            /** @return 发件人 */
            public String getFrom() {
                return from;
            }

            /**
             * @param from 发件人
             */
            public void setFrom(String from) {
                this.from = from;
            }

            /** @return 是否 STARTTLS */
            public boolean isStarttls() {
                return starttls;
            }

            /**
             * @param starttls 是否 STARTTLS
             */
            public void setStarttls(boolean starttls) {
                this.starttls = starttls;
            }

            /** @return 是否 SSL */
            public boolean isSsl() {
                return ssl;
            }

            /**
             * @param ssl 是否 SSL
             */
            public void setSsl(boolean ssl) {
                this.ssl = ssl;
            }

            /** @return 连接超时（毫秒） */
            public int getConnectTimeoutMs() {
                return connectTimeoutMs;
            }

            /**
             * @param connectTimeoutMs 连接超时（毫秒）
             */
            public void setConnectTimeoutMs(int connectTimeoutMs) {
                this.connectTimeoutMs = connectTimeoutMs;
            }

            /** @return 收件人 */
            public List<String> getRecipients() {
                return recipients;
            }

            /**
             * @param recipients 收件人
             */
            public void setRecipients(List<String> recipients) {
                this.recipients = recipients;
            }
        }
    }

    /** 定时作业周期（毫秒）。 */
    public static class Jobs {

        /** 心跳间隔。 */
        private long heartbeatMs = 15_000L;
        /** 租约回收间隔。 */
        private long leaseReapMs = 20_000L;
        /** 数据源探活间隔。 */
        private long healthProbeMs = 60_000L;
        /** 告警评估间隔。 */
        private long alertEvaluateMs = 30_000L;

        /** @return 心跳间隔 */
        public long getHeartbeatMs() {
            return heartbeatMs;
        }

        /**
         * @param heartbeatMs 心跳间隔
         */
        public void setHeartbeatMs(long heartbeatMs) {
            this.heartbeatMs = heartbeatMs;
        }

        /** @return 回收间隔 */
        public long getLeaseReapMs() {
            return leaseReapMs;
        }

        /**
         * @param leaseReapMs 回收间隔
         */
        public void setLeaseReapMs(long leaseReapMs) {
            this.leaseReapMs = leaseReapMs;
        }

        /** @return 探活间隔 */
        public long getHealthProbeMs() {
            return healthProbeMs;
        }

        /**
         * @param healthProbeMs 探活间隔
         */
        public void setHealthProbeMs(long healthProbeMs) {
            this.healthProbeMs = healthProbeMs;
        }

        /** @return 告警评估间隔 */
        public long getAlertEvaluateMs() {
            return alertEvaluateMs;
        }

        /**
         * @param alertEvaluateMs 告警评估间隔
         */
        public void setAlertEvaluateMs(long alertEvaluateMs) {
            this.alertEvaluateMs = alertEvaluateMs;
        }
    }

    /**
     * 增量采集配置（{@code dbsync.cdc.*}）。
     *
     * <p><b>为什么周期要可配</b>：采集是唯一会"周期性地主动查源库"的动作，
     * 周期直接决定源库压力。不同环境（在线库 vs 演练库）差距很大，
     * 写死在方法体里等于把生产环境的源库压力交给代码作者猜。
     *
     * <p><b>优先级</b>：任务级 {@code sync_task.poll_interval_ms}（{@code >0} 时生效）
     * → 这里的 {@link #getPollIntervalMs()}。这是"任务特殊、全局默认"的常规结构：
     * 只有个别任务需要更快/更慢时才去改任务级。
     */
    public static class Cdc {

        /** 采集轮询周期（毫秒），任务级未配置时生效。 */
        private long pollIntervalMs = 5_000L;

        /** 单次采集批大小（每张表每轮最多拉多少条源日志）。 */
        private int batchSize = 500;

        /** @return 采集周期（毫秒） */
        public long getPollIntervalMs() {
            return pollIntervalMs;
        }

        /**
         * @param pollIntervalMs 采集周期（毫秒）
         */
        public void setPollIntervalMs(long pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
        }

        /** @return 采集批大小 */
        public int getBatchSize() {
            return batchSize;
        }

        /**
         * @param batchSize 采集批大小
         */
        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }

    /**
     * 认领策略解析（集中一处，避免 Config 与 Job 各写一份 switch）。
     */
    static final class ClaimStrategyParser {
        private ClaimStrategyParser() {
        }

        /**
         * @param value 配置值
         * @return 枚举
         */
        static com.dbsync.common.enums.ClaimStrategy parse(String value) {
            if (value == null || value.isBlank()) {
                return com.dbsync.common.enums.ClaimStrategy.SKIP_LOCKED;
            }
            return com.dbsync.common.enums.ClaimStrategy.valueOf(
                    value.trim().toUpperCase(java.util.Locale.ROOT));
        }
    }
}
