package com.dbsync.cdc.sdk;

import com.dbsync.common.util.JsonUtil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 业务侧路由快照客户端（PRD CUT-01：切流变更 ≤3 秒生效）。
 *
 * <p><b>工作方式</b>：后台单线程按 {@code snapshotTtlMs / 3}（默认 3 秒 TTL → 1 秒一轮）
 * 轮询平台 {@code GET /api/sdk/route-snapshot?taskId=..}，把最新快照缓存起来；
 * 业务线程只调 {@link #decide(String, Object)} 读缓存，<b>永不阻塞在网络 IO 上</b>。
 *
 * <p><b>为什么轮询而不是长连接</b>：SDK 跑在业务 JVM 里，长连接（SSE/WebSocket）意味着
 * 断线重连、代理超时、连接数占用等一堆运维问题；3 秒粒度上，单线程每秒一个 GET
 * 对一个内网平台来说可以忽略（一个任务一条连接）。
 *
 * <p><b>三条必须成立的性质</b>（都有对应单测）：
 * <ol>
 *   <li><b>版本只增不减</b>：收到 {@code routeVersion <= 本地版本} 的快照直接丢弃。
 *       多实例/重试/网络乱序都可能让旧快照后到，若照单全收，一次乱序就能让业务
 *       从"已切 100%"退回"5% 灰度"，出现"读自己的写读不到"。</li>
 *   <li><b>灰度稳定</b>：按 {@code fnv1a64(String.valueOf(pk)) % 100 < ratio} 路由，
 *       与平台 {@code RouteRuleEngine} 同算法（见 {@link SdkRouteSnapshot#bucketOf}），
 *       同一主键在比例不变时结果恒定，<b>绝不用随机数</b>。</li>
 *   <li><b>失败不抛</b>：轮询失败沿用上一份快照 + 失败计数 + 采样日志；
 *       {@link #decide} 内部再兜一层 try/catch，任何异常都退化为"全走源库"。
 *       <b>例外</b>：401 / {@code E8004} / {@code TOKEN_INVALID} 这类"服务令牌失效"
 *       <b>不再当普通失败静默处理</b>——单独打 ERROR、置失效状态、回调上层
 *       （见 {@link #isTokenInvalid()} / {@link #setTokenInvalidListener(Consumer)}）。</li>
 * </ol>
 *
 * <p><b>服务令牌可刷新</b>：平台签发的 SDK 服务令牌是 8h 硬 TTL、不滑动，
 * 静态 {@code token} 用满 8h 后会突然 401 而"静默停更"。业务方可用
 * {@link #setTokenSupplier(Supplier)} 提供令牌源，客户端每轮轮询重新取值，
 * 在不改签发语义的前提下让快照持续更新；原 {@code String} 构造完全兼容。
 *
 * <p><b>不要在 SDK 里做 Spring 自动装配</b>：业务方是手工 {@code new RouteSnapshotClient(...)}
 * 后自己 {@link #start()} / {@link #stop()}（例如放在 {@code @PostConstruct} / {@code @PreDestroy}，
 * 但本类不依赖任何 Spring 注解）。
 *
 * <p>线程安全：快照引用是 {@code volatile}，更新走 {@code synchronized} 保证"比较 + 替换"原子。
 */
public final class RouteSnapshotClient {

    private static final Logger log = LoggerFactory.getLogger(RouteSnapshotClient.class);

    /** 默认快照陈旧上限（毫秒）——PRD CUT-01 的 3 秒生效要求。 */
    public static final long DEFAULT_SNAPSHOT_TTL_MS = 3000L;

    /** 服务令牌失效时的统一提示语（ERROR 日志与回调都会带上）。 */
    public static final String TOKEN_INVALID_MESSAGE = "SDK 服务令牌失效，路由快照已停止更新";

    /** 轮询间隔下限（毫秒），防止业务方把 TTL 配得过小把平台打爆。 */
    private static final long MIN_POLL_INTERVAL_MS = 200L;

    /** 日志采样间隔：失败时每 N 次打一条 warn。 */
    private static final long LOG_SAMPLE_EVERY = 20L;

    /** 快照端点路径。 */
    public static final String SNAPSHOT_PATH = "/api/sdk/route-snapshot";

    private final String baseUrl;
    /** 静态服务令牌（原 String 构造传入；未设置 supplier 时使用）。 */
    private final String token;
    private final long taskId;
    private final long snapshotTtlMs;
    private final SnapshotTransport transport;

    /** 可刷新的令牌源（优先于 {@link #token}）；每轮轮询都会重新取值。 */
    private volatile Supplier<String> tokenSupplier;
    /** 令牌失效通知回调（可为 null）：让业务方能重新签发令牌 / 触发告警。 */
    private volatile Consumer<String> tokenInvalidListener;
    /** 最近一次是否因令牌失效而停更；成功拉取后自动清除。 */
    private volatile boolean tokenInvalid;

    private final AtomicLong pollFailures = new AtomicLong(0L);
    private final AtomicLong pollSuccesses = new AtomicLong(0L);
    private final AtomicLong staleDiscarded = new AtomicLong(0L);

    private volatile SdkRouteSnapshot snapshot;
    private volatile ScheduledExecutorService scheduler;

    /**
     * 构造（生产用：自动创建 JDK HttpClient 传输）。
     *
     * @param baseUrl   平台地址（如 {@code http://dbsync.internal:8080}）
     * @param token     服务令牌（运维为该 SDK 配置的、具备 {@code TASK_VIEW} 权限的账号令牌）
     * @param taskId    同步任务 ID
     * @param ttlMs     快照陈旧上限（≤0 → 3000）
     * @param httpClient 可注入的 HttpClient（null → 新建）
     */
    public RouteSnapshotClient(String baseUrl, String token, long taskId, long ttlMs,
                               java.net.http.HttpClient httpClient) {
        this(baseUrl, token, taskId, ttlMs, new HttpSnapshotTransport(httpClient, null));
    }

    /**
     * 构造（简版：默认 TTL、默认传输）。
     *
     * @param baseUrl 平台地址
     * @param token   服务令牌
     * @param taskId  同步任务 ID
     */
    public RouteSnapshotClient(String baseUrl, String token, long taskId) {
        this(baseUrl, token, taskId, DEFAULT_SNAPSHOT_TTL_MS, new HttpSnapshotTransport());
    }

    /**
     * 构造（完整；测试注入 {@link SnapshotTransport}）。
     *
     * @param baseUrl   平台地址
     * @param token     服务令牌
     * @param taskId    同步任务 ID
     * @param ttlMs     快照陈旧上限（≤0 → 3000）
     * @param transport 传输端口（null → {@link HttpSnapshotTransport}）
     */
    public RouteSnapshotClient(String baseUrl, String token, long taskId, long ttlMs,
                               SnapshotTransport transport) {
        if (taskId <= 0L) {
            throw new IllegalArgumentException("taskId 必须为正数");
        }
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.token = token;
        this.taskId = taskId;
        this.snapshotTtlMs = ttlMs <= 0L ? DEFAULT_SNAPSHOT_TTL_MS : ttlMs;
        this.transport = transport == null ? new HttpSnapshotTransport() : transport;
        this.snapshot = SdkRouteSnapshot.fallback(taskId, this.snapshotTtlMs);
    }

    /**
     * 启动后台轮询（幂等：重复调用不会创建多个线程）。
     */
    public synchronized void start() {
        if (scheduler != null && !scheduler.isShutdown()) {
            log.debug("路由快照轮询已在运行 task={}", Long.valueOf(taskId));
            return;
        }
        long interval = pollIntervalMs();
        scheduler = Executors.newSingleThreadScheduledExecutor(threadFactory());
        scheduler.scheduleWithFixedDelay(this::pollQuietly, 0L, interval, TimeUnit.MILLISECONDS);
        log.info("路由快照轮询已启动 task={} interval={}ms ttl={}ms", Long.valueOf(taskId),
                Long.valueOf(interval), Long.valueOf(snapshotTtlMs));
    }

    /**
     * 停止后台轮询（幂等；停止后 {@link #decide} 仍可用，只是快照不再更新）。
     */
    public synchronized void stop() {
        ScheduledExecutorService current = scheduler;
        scheduler = null;
        if (current != null) {
            current.shutdownNow();
            log.info("路由快照轮询已停止 task={}", Long.valueOf(taskId));
        }
    }

    /**
     * 业务访问入口：读缓存即可，不做任何网络 IO。
     *
     * <p><b>永不抛异常</b>：任何内部异常（含快照解析后的畸形字段）都退化为
     * {@link RouteDecision#sourceOnly()}——业务降级为"只读写源库"总比 500 好。
     *
     * @param table 表名（可 null；null 时白名单规则不命中）
     * @param pk    主键（可 null；null 时桶固定为 0，路由稳定但不可分散）
     * @return 决策
     */
    public RouteDecision decide(String table, Object pk) {
        try {
            SdkRouteSnapshot current = snapshot;
            if (current == null) {
                return RouteDecision.sourceOnly();
            }
            boolean hits = current.hitsTarget(table, pk);
            boolean readTarget = SdkRouteSnapshot.TARGET_TARGET.equalsIgnoreCase(current.getReadFrom())
                    && hits;
            String writeTo = current.getWriteTo();
            boolean writeTarget;
            boolean writeSource;
            if (SdkRouteSnapshot.TARGET_TARGET.equalsIgnoreCase(writeTo)) {
                // 终态：停写源库，全量写目标
                writeTarget = true;
                writeSource = false;
            } else if (SdkRouteSnapshot.TARGET_BOTH.equalsIgnoreCase(writeTo)) {
                // 灰度双写：源库必写，命中的那部分额外写目标
                writeTarget = hits;
                writeSource = true;
            } else {
                writeTarget = false;
                writeSource = true;
            }
            RouteDecision decision = new RouteDecision(readTarget, writeTarget, writeSource);
            return decision.consistent() ? decision : RouteDecision.sourceOnly();
        } catch (RuntimeException e) {
            log.error("路由决策计算失败，降级为全源库 task={} table={}", Long.valueOf(taskId), table,
                    e);
            return RouteDecision.sourceOnly();
        }
    }

    /**
     * 立即拉取一次（运维排障/测试用；失败只计数不抛）。
     *
     * @return true 表示本次拉取并应用成功（含"丢弃旧版本"也算成功）
     */
    public boolean refreshOnce() {
        try {
            // 每轮都从 supplier 取新令牌（8h 硬 TTL 不滑动，静态 token 会到点 401）
            String body = transport.fetch(snapshotUrl(), currentToken());
            SdkRouteSnapshot fetched = parse(body);
            if (fetched == null) {
                onFailure(new IllegalStateException("路由快照响应为空"));
                return false;
            }
            pollSuccesses.incrementAndGet();
            apply(fetched);
            // 成功拉到（含因版本不新而丢弃）说明令牌已恢复，清掉失效标记
            tokenInvalid = false;
            return true;
        } catch (Exception e) {
            onFailure(e);
            return false;
        }
    }

    /**
     * 设置可刷新的服务令牌源（每轮轮询都会重新取值）。
     *
     * <p>平台 SDK 令牌是 8h 硬 TTL、不滑动：用静态 {@code token} 到点必然 401。
     * 传入 supplier（例如"每次向平台重新登录换取令牌"）即可让快照持续更新，
     * 无需改动 {@code AuthService} 的登录/签发语义。传 {@code null} 回退静态 token。
     *
     * @param tokenSupplier 令牌源（可为 null）
     */
    public void setTokenSupplier(Supplier<String> tokenSupplier) {
        this.tokenSupplier = tokenSupplier;
    }

    /**
     * 设置"服务令牌失效"回调（可为 null 表示取消）。
     *
     * <p>回调在轮询线程内触发，务必快速返回、不要在里面做阻塞网络 IO。
     *
     * @param listener 收到失效提示语的回调
     */
    public void setTokenInvalidListener(Consumer<String> listener) {
        this.tokenInvalidListener = listener;
    }

    /**
     * 最近一次是否因服务令牌失效而停更（成功拉取后自动清除）。
     *
     * @return true 表示当前处于"令牌失效、快照停更"状态，上层应重新签发令牌
     */
    public boolean isTokenInvalid() {
        return tokenInvalid;
    }

    /**
     * 当前快照（永不为 null：未拉取成功时是版本 0 的兜底快照）。
     *
     * @return 快照
     */
    public SdkRouteSnapshot current() {
        return snapshot;
    }

    /**
     * 当前生效的路由版本（未拉取成功过则为 0）。
     *
     * @return 版本
     */
    public long currentVersion() {
        return snapshot == null ? 0L : snapshot.getRouteVersion();
    }

    /** @return 任务 ID */
    public long taskId() {
        return taskId;
    }

    /** @return 快照陈旧上限（毫秒） */
    public long snapshotTtlMs() {
        return snapshotTtlMs;
    }

    /** @return 轮询失败次数（供监控上报） */
    public long pollFailures() {
        return pollFailures.get();
    }

    /** @return 轮询成功次数 */
    public long pollSuccesses() {
        return pollSuccesses.get();
    }

    /** @return 被丢弃的旧版本快照数（验证"版本只增不减"的观测点） */
    public long staleDiscarded() {
        return staleDiscarded.get();
    }

    /**
     * 应用一次拉取结果（版本只增不减）。
     *
     * @param fetched 新快照
     * @return true 表示已采纳；false 表示因版本不新而丢弃
     */
    private synchronized boolean apply(SdkRouteSnapshot fetched) {
        SdkRouteSnapshot current = snapshot;
        if (current != null && fetched.getRouteVersion() <= current.getRouteVersion()) {
            staleDiscarded.incrementAndGet();
            log.debug("丢弃非递增快照 task={} 本地={} 收到={}", Long.valueOf(taskId),
                    Long.valueOf(current.getRouteVersion()),
                    Long.valueOf(fetched.getRouteVersion()));
            return false;
        }
        snapshot = fetched;
        log.info("路由快照已更新 task={} version={} state={} read={}/{}% write={}",
                Long.valueOf(taskId), Long.valueOf(fetched.getRouteVersion()), fetched.getState(),
                fetched.getReadFrom(), Integer.valueOf(fetched.getGrayRatio()),
                fetched.getWriteTo());
        return true;
    }

    /**
     * 轮询一次并吞掉所有异常（后台线程绝不能因异常而终止）。
     */
    private void pollQuietly() {
        try {
            refreshOnce();
        } catch (Throwable e) {
            // 极端情况（OOM 之外的意外）：也必须活着，否则后续快照永远不再更新
            log.warn("路由快照轮询线程异常 task={}", Long.valueOf(taskId), e);
        }
    }

    /**
     * 失败处理：令牌失效单独上报，其余沿用旧快照 + 计数 + 采样日志。
     *
     * @param error 错误
     */
    private void onFailure(Exception error) {
        long failures = pollFailures.incrementAndGet();
        if (isTokenInvalidError(error)) {
            tokenInvalid = true;
            // 明确区分令牌失效：ERROR + 状态 + 回调，绝不只当"沿用旧快照"的普通失败静默处理
            log.error("{} task={} 请重新签发服务令牌（平台令牌为 8h 硬 TTL、不滑动）err={}",
                    TOKEN_INVALID_MESSAGE, Long.valueOf(taskId), error.toString(), error);
            Consumer<String> listener = tokenInvalidListener;
            if (listener != null) {
                try {
                    listener.accept(TOKEN_INVALID_MESSAGE);
                } catch (RuntimeException e) {
                    log.warn("令牌失效回调异常 task={}", Long.valueOf(taskId), e);
                }
            }
            return;
        }
        if (failures == 1L || failures % LOG_SAMPLE_EVERY == 0L) {
            log.warn("路由快照拉取失败（沿用上一份 v={}，累计 {} 次）task={} err={}",
                    Long.valueOf(currentVersion()), Long.valueOf(failures), Long.valueOf(taskId),
                    error.toString());
        }
    }

    /**
     * 当前生效令牌：优先取 supplier（每轮刷新），否则回退静态 token。
     *
     * @return 令牌
     */
    private String currentToken() {
        Supplier<String> supplier = this.tokenSupplier;
        if (supplier == null) {
            return token;
        }
        try {
            String fresh = supplier.get();
            return fresh == null ? token : fresh;
        } catch (RuntimeException e) {
            log.warn("获取 SDK 服务令牌失败，回退静态令牌 task={}", Long.valueOf(taskId), e);
            return token;
        }
    }

    /**
     * 判定是否为"服务令牌失效"（沿 cause 链找 401 / E8004 / TOKEN_INVALID）。
     * HttpSnapshotTransport 对非 200 抛 {@code "路由快照拉取失败 HTTP 401"}；
     * 平台侧也可能在错误体里给 {@code E8004} 或 {@code TOKEN_INVALID}。
     *
     * @param error 失败异常
     * @return true 表示令牌失效
     */
    private static boolean isTokenInvalidError(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String msg = String.valueOf(t.getMessage()).toUpperCase(Locale.ROOT);
            if (msg.contains("HTTP 401") || msg.contains("401 ") || msg.contains(" 401")
                    || msg.contains("E8004") || msg.contains("TOKEN_INVALID")
                    || msg.contains("TOKEN EXPIRED") || msg.contains("TOKEN INVALID")
                    || msg.contains("UNAUTHORIZED")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析响应体（兼容"包了 ApiResponse 信封"与"裸快照"两种形态）。
     *
     * @param body 响应体
     * @return 快照；无法解析返回 null
     */
    @SuppressWarnings("unchecked")
    private SdkRouteSnapshot parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        Map<String, Object> root = JsonUtil.parseObject(body);
        Object data = root.get("data");
        Map<String, Object> payload = data instanceof Map
                ? (Map<String, Object>) data : root;
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> rules = new ArrayList<>();
        Object rawRules = payload.get("rules");
        if (rawRules instanceof List) {
            for (Object item : (List<Object>) rawRules) {
                if (item instanceof Map) {
                    rules.add(new LinkedHashMap<>((Map<String, Object>) item));
                }
            }
        }
        return new SdkRouteSnapshot(longOf(payload.get("taskId"), taskId),
                longOf(payload.get("routeVersion"), 0L),
                stringOf(payload.get("state"), SdkRouteSnapshot.STATE_NONE),
                stringOf(payload.get("readFrom"), SdkRouteSnapshot.TARGET_SOURCE),
                stringOf(payload.get("writeTo"), SdkRouteSnapshot.TARGET_SOURCE),
                (int) longOf(payload.get("grayRatio"), 0L), rules,
                longOf(payload.get("snapshotTtlMs"), snapshotTtlMs),
                instantOf(payload.get("generatedAt")));
    }

    /**
     * 快照 URL。
     *
     * @return 形如 {@code http://host:8080/api/sdk/route-snapshot?taskId=1}
     */
    private String snapshotUrl() {
        return baseUrl + SNAPSHOT_PATH + "?taskId=" + taskId;
    }

    /**
     * 轮询间隔：TTL 的 1/3（TTL 是"最坏情况下允许多陈旧"，取 1/3 才能在
     * "拉取耗时 + 调度抖动"之后仍然满足 ≤TTL 生效），并有 200ms 下限。
     *
     * @return 间隔毫秒
     */
    private long pollIntervalMs() {
        return Math.max(MIN_POLL_INTERVAL_MS, Math.min(snapshotTtlMs, snapshotTtlMs / 3L));
    }

    /**
     * 守护线程工厂（业务应用退出时不因本线程而挂住）。
     *
     * @return 线程工厂
     */
    private ThreadFactory threadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "dbsync-route-snapshot-" + taskId);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 去掉尾部斜杠。
     *
     * @param url 原始地址
     * @return 规范化地址
     */
    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("平台地址不能为空");
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * 宽松长整型解析。
     *
     * @param value        原值
     * @param defaultValue 默认值
     * @return 长整型
     */
    private static long longOf(Object value, long defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 宽松字符串解析。
     *
     * @param value        原值
     * @param defaultValue 默认值
     * @return 字符串
     */
    private static String stringOf(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? defaultValue : text;
    }

    /**
     * ISO-8601 时间解析（失败取 EPOCH，不让展示字段影响路由正确性）。
     *
     * @param value 原值
     * @return 时间
     */
    private static Instant instantOf(Object value) {
        if (value == null) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(String.valueOf(value).trim());
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }
}
