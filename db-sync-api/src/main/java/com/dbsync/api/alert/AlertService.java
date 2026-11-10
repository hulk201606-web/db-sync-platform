package com.dbsync.api.alert;

import com.dbsync.core.port.AlertPort;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 告警服务（T45 / OBS-02：6 条规则 + 持续时长 + 静默窗口）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>"持续多久才告警"</b>：每条规则有一个 {@code sustained} 时长，
 *       条件首次为真时只记录起点，连续为真超过阈值才真正 {@link #fire}；
 *       一旦条件为假立即清零（避免"闪断累积"）；</li>
 *   <li><b>静默窗口去重</b>：同一条告警在静默期内不重复发送（30min/10min/1h 按规则不同），
 *       <b>对所有渠道统一生效</b>（日志 / Webhook / 邮件共用 {@code dispatch} 这一条路径，
 *       邮件不另开去重逻辑，否则"同一个故障发 3 封邮件"只是时间问题）；</li>
 *   <li><b>渠道</b>：LOG / WEBHOOK / EMAIL 均已实现。EMAIL 经 {@link MailTransport} 端口下发，
 *       未配置或发送失败一律<b>静默降级</b>（返回 false + 一次性 WARN），
 *       绝不让邮件故障打断告警链路；</li>
 *   <li><b>时钟可注入</b>：{@link Clock} 让"持续 60 秒"能在单测里被精确驱动，
 *       不用 sleep 真实时间。</li>
 * </ul>
 */
public class AlertService implements AlertPort {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    /** 规则编码。 */
    public static final String RULE_LAG = "LAG_HIGH";
    /** 规则编码。 */
    public static final String RULE_FAIL_RATE = "FAIL_RATE_HIGH";
    /** 规则编码。 */
    public static final String RULE_TASK_FAILED = "TASK_FAILED";
    /** 规则编码。 */
    public static final String RULE_DEAD_LETTER = "DEADLETTER_NEW";
    /** 规则编码。 */
    public static final String RULE_DATASOURCE_DOWN = "DATASOURCE_DOWN";
    /** 规则编码。 */
    public static final String RULE_LEASE_TAKEOVER = "LEASE_TAKEOVER";

    /** 告警渠道。 */
    public enum Channel {
        /** 只打日志（默认，永远可用）。 */
        LOG,
        /** HTTP POST JSON。 */
        WEBHOOK,
        /** 邮件（经 {@link MailTransport} 下发，未配置时自动降级为不发）。 */
        EMAIL,
        /** 自定义（由外部注入处理器，本期为 no-op）。 */
        CUSTOM
    }

    /**
     * 邮件渠道的可用性设置（由 server 层从 {@code dbsync.alert.mail.*} 装配）。
     *
     * <p>{@code enabled=false} 或收件人为空都视为"未配置"：此时
     * {@link #sendEmail} 返回 false 并只 WARN 一次，避免每个评估周期刷一条日志。
     */
    public static final class MailOptions {

        /** 未配置的实例（enabled=false、无收件人）。 */
        public static final MailOptions DISABLED = new MailOptions(false, List.of());

        private final boolean enabled;
        private final List<String> recipients;

        /**
         * 构造。
         *
         * @param enabled    是否启用邮件渠道
         * @param recipients 收件人列表（null 归一为空列表）
         */
        public MailOptions(boolean enabled, List<String> recipients) {
            this.enabled = enabled;
            this.recipients = recipients == null ? List.of() : List.copyOf(recipients);
        }

        /** @return 是否启用 */
        public boolean isEnabled() {
            return enabled;
        }

        /** @return 不可变收件人列表 */
        public List<String> getRecipients() {
            return recipients;
        }
    }

    /**
     * 告警数据源（由 server 层用 DAO/指标装配，便于单测替换）。
     */
    public interface AlertSource {

        /** @return 增量延迟 P95（毫秒） */
        long lagP95Ms();

        /** @return 延迟阈值（毫秒） */
        long lagThresholdMs();

        /** @return 最近 15 分钟失败率 */
        double failRate();

        /** @return 失败率阈值 */
        double failRateThreshold();

        /** @return 处于 FAILED 的任务 ID 列表 */
        List<Long> failedTaskIds();

        /** @return 未处理死信数 */
        long deadLetterOpen();

        /** @return 探活状态为 DOWN 的数据源数 */
        int downDataSourceCount();

        /** @return 最近 10 分钟的租约接管次数 */
        int takeoverInLast10Minutes();

        /**
         * 任务名（供邮件/Webhook 正文展示"是哪张单子出问题"，只给 ID 等于让值班去查库）。
         *
         * <p><b>默认返回 null</b>：取数源实现不该被"为了标题好看"而强制依赖任务 DAO；
         * 返回 null 时正文退化为展示任务 ID，不影响告警送达。
         * 生产实现（{@code AlertSourceAdapter}）会覆盖它，且只在真正下发邮件时才被调用
         * （不在 5 秒一次的评估里查库）。
         *
         * @param taskId 任务 ID
         * @return 任务名；取不到返回 null
         */
        default String taskNameOf(long taskId) {
            return null;
        }
    }

    /** 告警记录。 */
    public static final class AlertRecord {
        private final String rule;
        private final String title;
        private final String detail;
        private final Map<String, Object> tags;
        private final Instant firedAt;
        private final Set<Channel> channels;

        AlertRecord(String rule, String title, String detail, Map<String, Object> tags,
                    Instant firedAt, Set<Channel> channels) {
            this.rule = rule;
            this.title = title;
            this.detail = detail;
            this.tags = Collections.unmodifiableMap(new LinkedHashMap<>(tags));
            this.firedAt = firedAt;
            this.channels = Collections.unmodifiableSet(EnumSet.copyOf(channels));
        }

        /** @return 规则编码 */
        public String getRule() {
            return rule;
        }

        /** @return 标题 */
        public String getTitle() {
            return title;
        }

        /** @return 详情 */
        public String getDetail() {
            return detail;
        }

        /** @return 标签 */
        public Map<String, Object> getTags() {
            return tags;
        }

        /** @return 触发时间 */
        public Instant getFiredAt() {
            return firedAt;
        }

        /** @return 已下发渠道 */
        public Set<Channel> getChannels() {
            return channels;
        }

        @Override
        public String toString() {
            return "Alert[" + rule + "] " + title + " @" + firedAt;
        }
    }

    /** 规则元数据。 */
    private static final class RuleSpec {
        private final String rule;
        private final Duration sustained;
        private final Duration silence;
        private final Set<Channel> channels;

        private RuleSpec(String rule, Duration sustained, Duration silence, Set<Channel> channels) {
            this.rule = rule;
            this.sustained = sustained;
            this.silence = silence;
            this.channels = channels;
        }
    }

    private static final Map<String, RuleSpec> SPECS = buildSpecs();

    private final AlertSource source;
    private final Clock clock;
    private final Set<Channel> enabledChannels;
    private final String webhookUrl;
    private final Duration httpTimeout;
    private final HttpClient httpClient;
    private final MailTransport mailTransport;
    private final MailOptions mailOptions;
    private final Map<String, Instant> conditionSince = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastFired = new ConcurrentHashMap<>();
    private final List<AlertRecord> history = Collections.synchronizedList(new ArrayList<>());
    private long lastDeadLetterOpen;
    private boolean deadLetterPrimed;
    /** "邮件未配置"只提示一次（评估周期 5s，不设标记会刷爆日志）。 */
    private volatile boolean mailUnavailableWarned;

    /**
     * 构造（最简：只打日志）。
     *
     * @param source 数据源
     */
    public AlertService(AlertSource source) {
        this(source, Clock.systemUTC(), EnumSet.of(Channel.LOG), null, Duration.ofSeconds(5L));
    }

    /**
     * 构造（无邮件渠道）。
     *
     * @param source          数据源
     * @param clock           时钟（可注入以便单测）
     * @param enabledChannels 启用渠道
     * @param webhookUrl      Webhook 地址（启用 WEBHOOK 时必填）
     * @param httpTimeout     Webhook 超时
     */
    public AlertService(AlertSource source, Clock clock, Set<Channel> enabledChannels,
                        String webhookUrl, Duration httpTimeout) {
        this(source, clock, enabledChannels, webhookUrl, httpTimeout, null, MailOptions.DISABLED);
    }

    /**
     * 构造（含邮件渠道）。
     *
     * @param source          数据源
     * @param clock           时钟（可注入以便单测）
     * @param enabledChannels 启用渠道
     * @param webhookUrl      Webhook 地址（启用 WEBHOOK 时必填）
     * @param httpTimeout     Webhook 超时
     * @param mailTransport   邮件发送端口（null 表示不可用）
     * @param mailOptions     邮件渠道设置（null 视为未配置）
     */
    public AlertService(AlertSource source, Clock clock, Set<Channel> enabledChannels,
                        String webhookUrl, Duration httpTimeout, MailTransport mailTransport,
                        MailOptions mailOptions) {
        this.source = source;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.enabledChannels = enabledChannels == null || enabledChannels.isEmpty()
                ? EnumSet.of(Channel.LOG) : EnumSet.copyOf(enabledChannels);
        this.webhookUrl = webhookUrl;
        this.httpTimeout = httpTimeout == null ? Duration.ofSeconds(5L) : httpTimeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.httpTimeout).build();
        this.mailTransport = mailTransport;
        this.mailOptions = mailOptions == null ? MailOptions.DISABLED : mailOptions;
    }

    /**
     * 周期性评估 6 条规则（由 {@code HeartbeatJob} 每 5 秒调用）。
     *
     * @return 本次真正触发的告警
     */
    public List<AlertRecord> evaluate() {
        List<AlertRecord> fired = new ArrayList<>();

        long lag = safe(source::lagP95Ms);
        long lagThreshold = safe(source::lagThresholdMs);
        if (lagThreshold > 0L) {
            evaluateRule(RULE_LAG, lag > lagThreshold, fired,
                    "增量延迟超阈值", "lag.p95=" + lag + "ms > " + lagThreshold + "ms",
                    tags("lagP95Ms", Long.valueOf(lag), "threshold", Long.valueOf(lagThreshold)));
        }

        double failRate = safeDouble(source::failRate);
        double failThreshold = safeDouble(source::failRateThreshold);
        if (failThreshold > 0.0d) {
            evaluateRule(RULE_FAIL_RATE, failRate > failThreshold, fired,
                    "增量失败率超阈值",
                    "failRate=" + round(failRate) + " > " + round(failThreshold),
                    tags("failRate", Double.valueOf(round(failRate)),
                            "threshold", Double.valueOf(round(failThreshold))));
        }

        List<Long> failedTasks = safeList(source::failedTaskIds);
        evaluateRule(RULE_TASK_FAILED, !failedTasks.isEmpty(), fired, "任务失败",
                "FAILED 任务数=" + failedTasks.size() + " ids=" + failedTasks,
                tags("taskIds", failedTasks));

        long openNow = safe(source::deadLetterOpen);
        boolean deadLetterGrew = deadLetterPrimed && openNow > lastDeadLetterOpen;
        lastDeadLetterOpen = openNow;
        deadLetterPrimed = true;
        evaluateRule(RULE_DEAD_LETTER, deadLetterGrew, fired, "死信新增",
                "open=" + openNow + "（上次=" + Math.max(0L, openNow - 1L) + "）",
                tags("open", Long.valueOf(openNow)));

        int downDs = safeInt(source::downDataSourceCount);
        evaluateRule(RULE_DATASOURCE_DOWN, downDs > 0, fired, "数据源不可用",
                "DOWN 数据源数=" + downDs, tags("downCount", Integer.valueOf(downDs)));

        int takeover = safeInt(source::takeoverInLast10Minutes);
        evaluateRule(RULE_LEASE_TAKEOVER, takeover > 3, fired, "租约接管异常",
                "10 分钟内接管 " + takeover + " 次（阈值 3）", tags("takeover", Integer.valueOf(takeover)));

        return fired;
    }

    @Override
    public void fire(String rule, String title, String detail, Map<String, Object> tags) {
        dispatch(rule == null ? "MANUAL" : rule, title, detail,
                tags == null ? Map.of() : tags);
    }

    /**
     * 已触发告警（最近 200 条，供大盘）。
     *
     * @return 告警列表（时间升序）
     */
    public List<AlertRecord> recent() {
        synchronized (history) {
            int from = Math.max(0, history.size() - 200);
            return new ArrayList<>(history.subList(from, history.size()));
        }
    }

    /**
     * 当前启用渠道。
     *
     * @return 渠道集合
     */
    public Set<Channel> channels() {
        return Collections.unmodifiableSet(enabledChannels);
    }

    /**
     * 判定某规则是否在静默期内（测试与大盘用）。
     *
     * @param rule 规则
     * @return 静默中返回 true
     */
    public boolean isSilenced(String rule) {
        RuleSpec spec = SPECS.get(rule);
        Instant last = lastFired.get(rule);
        if (spec == null || last == null) {
            return false;
        }
        return clock.instant().isBefore(last.plus(spec.silence));
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void evaluateRule(String rule, boolean condition, List<AlertRecord> fired, String title,
                              String detail, Map<String, Object> tags) {
        RuleSpec spec = SPECS.get(rule);
        if (spec == null) {
            return;
        }
        Instant now = clock.instant();
        if (!condition) {
            conditionSince.remove(rule);
            return;
        }
        Instant since = conditionSince.get(rule);
        if (since == null) {
            conditionSince.put(rule, now);
            since = now;
        }
        if (Duration.between(since, now).compareTo(spec.sustained) < 0) {
            return;
        }
        if (isSilenced(rule)) {
            return;
        }
        AlertRecord record = dispatch(rule, title, detail, tags);
        if (record != null) {
            fired.add(record);
        }
    }

    private AlertRecord dispatch(String rule, String title, String detail,
                                Map<String, Object> tags) {
        RuleSpec spec = SPECS.get(rule);
        Set<Channel> channels = spec == null ? enabledChannels
                : intersect(enabledChannels, spec.channels);
        if (channels.isEmpty()) {
            return null;
        }
        Instant now = clock.instant();
        lastFired.put(rule, now);
        if (channels.contains(Channel.LOG)) {
            log.warn("告警[{}] {} | {} | tags={}", rule, title, detail, tags);
        }
        if (channels.contains(Channel.WEBHOOK)) {
            sendWebhook(rule, title, detail, tags);
        }
        if (channels.contains(Channel.EMAIL)) {
            // 邮件走与 Webhook 完全相同的下发点：静默去重已在 evaluateRule 里做过，
            // 这里只负责"发"，不负责"要不要发"（两套去重逻辑必然漂移）。
            sendEmail(rule, title, detail, tags);
        }
        AlertRecord record = new AlertRecord(rule, title, detail, tags, now, channels);
        history.add(record);
        if (history.size() > 1000) {
            history.remove(0);
        }
        return record;
    }

    private void sendWebhook(String rule, String title, String detail, Map<String, Object> tags) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.debug("未配置 webhook 地址，跳过下发 rule={}", rule);
            return;
        }
        String body = "{\"rule\":\"" + escape(rule) + "\",\"title\":\"" + escape(title)
                + "\",\"detail\":\"" + escape(detail) + "\",\"firedAt\":\""
                + clock.instant() + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .timeout(httpTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warn("Webhook 下发失败 rule={} status={}", rule,
                        Integer.valueOf(response.statusCode()));
            }
        } catch (IOException e) {
            log.warn("Webhook 下发异常 rule={} err={}", rule, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Webhook 下发被中断 rule={}", rule);
        } catch (RuntimeException e) {
            log.warn("Webhook 下发非法参数 rule={} err={}", rule, e.toString());
        }
    }

    /**
     * 邮件渠道（OBS-02）。
     *
     * <p><b>绝不打断告警链路</b>：本方法<b>不会</b>向上抛任何异常。
     * <ul>
     *   <li>未配置（{@code enabled=false} / 无收件人 / 无 transport）→ 返回 false，
     *       且只 WARN 一次（评估周期 5~30s，不设一次性标记会把日志刷满）；</li>
     *   <li>SMTP 连接失败 / 认证失败 / 超时 → 捕获 {@link RuntimeException}，
     *       {@code log.error} 后返回 false。日志与 Webhook 渠道不受影响。</li>
     * </ul>
     *
     * <p>邮件内容包含：规则编码、标题、任务（若上下文有）、当前值/阈值（tags 逐项）、
     * 触发时间、跳转链接（上下文带 {@code link}/{@code url} 或 {@code taskId} 时）。
     *
     * @param rule   规则编码（主题里必须出现，便于邮件规则自动分派）
     * @param title  标题
     * @param detail 详情
     * @param tags   标签（当前值/阈值等上下文）
     * @return true 表示已交给 SMTP；false 表示未配置或发送失败
     */
    public boolean sendEmail(String rule, String title, String detail, Map<String, Object> tags) {
        Map<String, Object> context = tags == null ? Map.of() : tags;
        if (mailTransport == null || !mailOptions.isEnabled()
                || mailOptions.getRecipients().isEmpty()) {
            if (!mailUnavailableWarned) {
                mailUnavailableWarned = true;
                log.warn("邮件告警渠道未启用或未配置收件人，后续邮件告警将被跳过"
                        + "（dbsync.alert.mail.enabled / recipients 检查）rule={}", rule);
            }
            return false;
        }
        Instant now = clock.instant();
        String subject = "[DB-Sync][" + rule + "] " + safeText(title);
        String text = buildMailBody(rule, title, detail, context, now);
        try {
            return mailTransport.send(new MailMessage(mailOptions.getRecipients(), subject, text));
        } catch (RuntimeException e) {
            // 第三方 MailTransport 实现未必守约，这里再兜一层：
            // 邮件故障绝不能让"任务失败"这类关键告警整条链路失败
            log.error("邮件告警下发异常（已忽略，不影响其它渠道）rule={} err={}", rule,
                    e.toString());
            return false;
        }
    }

    /**
     * 拼邮件正文（纯文本，按"一眼能看懂"的顺序排）。
     *
     * @param rule   规则编码
     * @param title  标题
     * @param detail 详情
     * @param tags   上下文
     * @param now    触发时间
     * @return 正文
     */
    private String buildMailBody(String rule, String title, String detail,
                                 Map<String, Object> tags, Instant now) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("规则编码：").append(safeText(rule)).append('\n');
        sb.append("告警标题：").append(safeText(title)).append('\n');
        String task = describeTask(tags);
        if (task != null) {
            sb.append("关联任务：").append(task).append('\n');
        }
        String currentAndThreshold = describeTags(tags);
        if (currentAndThreshold != null) {
            sb.append("当前值/阈值：").append(currentAndThreshold).append('\n');
        }
        sb.append("触发时间：").append(now).append('\n');
        sb.append("详情：").append(safeText(detail)).append('\n');
        String link = describeLink(tags);
        if (link != null) {
            sb.append("跳转链接：").append(link).append('\n');
        }
        sb.append('\n').append("—— 本邮件由 db-sync-platform 告警引擎自动发送，请勿直接回复。");
        return sb.toString();
    }

    /**
     * 从上下文里取"关联任务"：优先用显式 {@code taskName}，否则用任务 ID 反查任务名。
     *
     * <p>反查只在<b>真正要把邮件发出去</b>时发生（不在 5 秒一次的评估循环里），
     * 因此"为了标题好看"多出来的这次查询不会给常规路径增加开销。
     *
     * @param tags 上下文
     * @return 任务描述；无则 null
     */
    private String describeTask(Map<String, Object> tags) {
        Object name = firstPresent(tags, "taskname");
        if (name != null) {
            return String.valueOf(name);
        }
        Object ids = firstPresent(tags, "taskids", "taskid");
        if (ids == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : flattenIds(ids)) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            String resolved = resolveTaskName(id);
            sb.append(resolved == null ? "ID " + id : resolved + "（ID " + id + "）");
        }
        return sb.length() == 0 ? String.valueOf(ids) : sb.toString();
    }

    /**
     * 把上下文里的任务 ID（可能是单个数字，也可能是列表）摊平成 ID 列表。
     *
     * @param ids 上下文值
     * @return ID 列表（最多 5 个，避免一封邮件塞进上千个 ID）
     */
    private static List<Long> flattenIds(Object ids) {
        List<Long> result = new ArrayList<>();
        if (ids instanceof Number) {
            result.add(Long.valueOf(((Number) ids).longValue()));
            return result;
        }
        if (ids instanceof Iterable) {
            for (Object item : (Iterable<?>) ids) {
                if (result.size() >= 5) {
                    break;
                }
                if (item instanceof Number) {
                    result.add(Long.valueOf(((Number) item).longValue()));
                }
            }
        }
        return result;
    }

    /**
     * 反查任务名（取数源异常时静默降级为 null）。
     *
     * @param taskId 任务 ID
     * @return 任务名；取不到返回 null
     */
    private String resolveTaskName(long taskId) {
        try {
            return source.taskNameOf(taskId);
        } catch (RuntimeException e) {
            log.debug("反查任务名失败 taskId={} err={}", Long.valueOf(taskId), e.toString());
            return null;
        }
    }

    /**
     * 从上下文里拼"当前值/阈值"（tags 里除跳转外的全部键值）。
     *
     * @param tags 上下文
     * @return 描述；无则 null
     */
    private static String describeTags(Map<String, Object> tags) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : tags.entrySet()) {
            String key = entry.getKey();
            if (key == null || "link".equalsIgnoreCase(key) || "url".equalsIgnoreCase(key)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(key).append('=').append(entry.getValue());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 从上下文里取跳转链接（显式 {@code link}/{@code url} 优先，否则用任务 ID 拼）。
     *
     * @param tags 上下文
     * @return 链接；无则 null
     */
    private static String describeLink(Map<String, Object> tags) {
        Object explicit = firstPresent(tags, "link", "url");
        if (explicit != null && !String.valueOf(explicit).isBlank()) {
            return String.valueOf(explicit);
        }
        List<Long> ids = flattenIds(firstPresent(tags, "taskid", "taskids"));
        return ids.isEmpty() ? null : "/tasks/" + ids.get(0);
    }

    /**
     * 取第一个存在的非空值（忽略大小写地匹配多个候选键）。
     *
     * @param tags 上下文
     * @param keys 候选键
     * @return 值；无则 null
     */
    private static Object firstPresent(Map<String, Object> tags, String... keys) {
        for (String candidate : keys) {
            Object value = tags.get(candidate);
            if (value != null) {
                return value;
            }
            for (Map.Entry<String, Object> entry : tags.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(candidate)
                        && entry.getValue() != null) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    /**
     * null 安全转文本。
     *
     * @param value 值
     * @return 文本（null → 空串）
     */
    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    /**
     * 六条规则的元数据（持续时长 / 静默窗口 / 渠道）。
     *
     * <p><b>六条规则全部包含 EMAIL</b>：邮件是"人不在电脑前"时唯一能触达的渠道，
     * 只给部分规则开邮件等于在值班场景下留盲区。是否真的发信由
     * {@code dbsync.alert.channels} 与 {@code dbsync.alert.mail.enabled} 两层开关控制，
     * 因此这里放开不会给默认部署增加任何外部依赖。
     *
     * @return 规则表（不可变）
     */
    private static Map<String, RuleSpec> buildSpecs() {
        Map<String, RuleSpec> specs = new LinkedHashMap<>();
        specs.put(RULE_LAG, new RuleSpec(RULE_LAG, Duration.ofSeconds(60L),
                Duration.ofMinutes(30L),
                EnumSet.of(Channel.LOG, Channel.WEBHOOK, Channel.EMAIL)));
        specs.put(RULE_FAIL_RATE, new RuleSpec(RULE_FAIL_RATE, Duration.ofSeconds(60L),
                Duration.ofMinutes(30L),
                EnumSet.of(Channel.LOG, Channel.WEBHOOK, Channel.EMAIL)));
        specs.put(RULE_TASK_FAILED, new RuleSpec(RULE_TASK_FAILED, Duration.ZERO,
                Duration.ofHours(1L), EnumSet.of(Channel.LOG, Channel.WEBHOOK, Channel.EMAIL)));
        specs.put(RULE_DEAD_LETTER, new RuleSpec(RULE_DEAD_LETTER, Duration.ZERO,
                Duration.ofMinutes(10L),
                EnumSet.of(Channel.LOG, Channel.WEBHOOK, Channel.EMAIL)));
        specs.put(RULE_DATASOURCE_DOWN, new RuleSpec(RULE_DATASOURCE_DOWN, Duration.ofSeconds(60L),
                Duration.ofMinutes(30L),
                EnumSet.of(Channel.LOG, Channel.WEBHOOK, Channel.EMAIL)));
        specs.put(RULE_LEASE_TAKEOVER, new RuleSpec(RULE_LEASE_TAKEOVER, Duration.ZERO,
                Duration.ofHours(1L), EnumSet.of(Channel.LOG, Channel.WEBHOOK, Channel.EMAIL)));
        return Collections.unmodifiableMap(specs);
    }

    private static Set<Channel> intersect(Set<Channel> left, Set<Channel> right) {
        Set<Channel> result = EnumSet.noneOf(Channel.class);
        for (Channel channel : left) {
            if (right.contains(channel)) {
                result.add(channel);
            }
        }
        return result;
    }

    private static Map<String, Object> tags(Object... pairs) {
        Map<String, Object> tags = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i] != null) {
                tags.put(String.valueOf(pairs[i]).toLowerCase(Locale.ROOT), pairs[i + 1]);
            }
        }
        return tags;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    private static long safe(java.util.function.LongSupplier supplier) {
        try {
            return supplier.getAsLong();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static int safeInt(java.util.function.IntSupplier supplier) {
        try {
            return supplier.getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static double safeDouble(java.util.function.DoubleSupplier supplier) {
        try {
            return supplier.getAsDouble();
        } catch (RuntimeException e) {
            return 0.0d;
        }
    }

    private static List<Long> safeList(java.util.function.Supplier<List<Long>> supplier) {
        try {
            List<Long> value = supplier.get();
            return value == null ? List.of() : value;
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
