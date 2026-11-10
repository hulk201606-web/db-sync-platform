package com.dbsync.cdc;

import com.dbsync.common.model.SyncTask;
import com.dbsync.metadata.dao.SyncTaskDao;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 采集调度器（<b>按 taskId 启停</b>）：把 {@link ChangeCollector} 的一轮采集
 * 变成"每 N 秒一次的周期性动作"，并负责重入保护与失败退避。
 *
 * <p><b>为什么它是独立类而不塞进 {@link ChangeConsumer}</b>：采集与消费是两个方向
 * （源库 → 队列 / 队列 → 目标库），失败模式与周期完全不同（采集怕把源库压垮，
 * 消费怕把目标库压垮）。分开之后各自可以独立退避；但它们的<b>生命周期必须同源</b>——
 * 这由 {@code CdcRuntimeManager} 同时启停两者保证（见该类 start/stop）。
 *
 * <p><b>重入保护</b>（必须）：同一个 taskId 的两轮采集不得并发。
 * 并发跑会把"读位点 → 推位点"变成竞态，同一批日志被搬两遍。
 * 保护方式是"本任务上一轮没结束时下一轮直接跳过 + {@code log.debug}"，
 * 而不是排队等待——排队只会让积压无限增长，而采集是幂等可重试的。
 *
 * <p><b>退避</b>：连续失败按 {@link CaptureBackoff} 的阶梯放大等待
 * （5s → 10s → 30s → 60s → 120s → 封顶 5min），成功后立刻回到正常周期。
 * 日志每 10 次失败打一条 WARN（第一次也打），其余降为 DEBUG：
 * 源库整夜不可用时，几万条同质 WARN 会把真正需要被看见的错误淹掉。
 *
 * <p><b>周期优先级</b>：任务级 {@code sync_task.poll_interval_ms}
 * → 全局默认（{@code dbsync.cdc.poll-interval-ms}）。
 * 任务级为 {@code <=0} 时才回落，绝不在方法体里写死秒数。
 *
 * <p><b>线程</b>：一个守护线程池（默认 2 个线程）承载所有任务的一次性再调度。
 * 用"跑完再排下一次"（{@code schedule} + 自旋重排）而不是
 * {@code scheduleAtFixedRate}，是因为退避需要<b>动态</b>改变间隔——
 * 固定频率调度器没法在失败后把周期从 5s 拉到 5min。
 */
public class CdcCaptureScheduler {

    private static final Logger log = LoggerFactory.getLogger(CdcCaptureScheduler.class);

    /** 全局默认采集周期（调用方未配置时的兜底，与 {@code sync_task.poll_interval_ms} 的列默认值一致）。 */
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(5L);

    /** 连续失败多少次时打一条 WARN（另加第一次）。 */
    private static final int WARN_SAMPLE_EVERY = 10;

    /** 调度线程数：采集轮次是 IO 型且互相独立，2 个足够；再多只是抢源库连接。 */
    private static final int SCHEDULER_THREADS = 2;

    private final ChangeCollector collector;
    private final SyncTaskDao syncTaskDao;
    private final Duration defaultPollInterval;
    private final ScheduledExecutorService scheduler;
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 构造（自建守护线程池）。
     *
     * @param collector           一轮采集的执行者
     * @param syncTaskDao         任务 DAO（读状态/模式/周期）
     * @param defaultPollInterval 全局默认采集周期（null → {@link #DEFAULT_POLL_INTERVAL}）
     */
    public CdcCaptureScheduler(ChangeCollector collector, SyncTaskDao syncTaskDao,
                               Duration defaultPollInterval) {
        this(collector, syncTaskDao, defaultPollInterval, newScheduler());
    }

    /**
     * 构造（线程池外置，便于单测直接驱动）。
     *
     * @param collector           一轮采集的执行者
     * @param syncTaskDao         任务 DAO
     * @param defaultPollInterval 全局默认采集周期（null → {@link #DEFAULT_POLL_INTERVAL}）
     * @param scheduler           调度线程池（不可 null）
     */
    public CdcCaptureScheduler(ChangeCollector collector, SyncTaskDao syncTaskDao,
                               Duration defaultPollInterval, ScheduledExecutorService scheduler) {
        if (collector == null) {
            throw new IllegalArgumentException("CdcCaptureScheduler 需要 ChangeCollector");
        }
        this.collector = collector;
        this.syncTaskDao = syncTaskDao;
        this.defaultPollInterval = defaultPollInterval == null || defaultPollInterval.isNegative()
                || defaultPollInterval.isZero() ? DEFAULT_POLL_INTERVAL : defaultPollInterval;
        this.scheduler = scheduler == null ? newScheduler() : scheduler;
    }

    /**
     * 启动某任务的采集调度（幂等：已在跑则只记日志）。
     *
     * <p><b>不需要采集的任务不登记</b>（{@code SDK_WRITE}、非 RUNNING）：登记了也只会
     * 每 5 秒空转一次。返回 false 表示"没有为它建调度"，调用方不需要当成错误。
     *
     * @param taskId 任务 ID
     * @return true 表示已登记并开始周期采集
     */
    public synchronized boolean start(long taskId) {
        if (closed.get()) {
            log.warn("采集调度器已关闭，拒绝启动 task={}", taskId);
            return false;
        }
        if (sessions.containsKey(Long.valueOf(taskId))) {
            log.debug("采集调度已在运行，忽略重复启动 task={}", taskId);
            return true;
        }
        Optional<SyncTask> task;
        try {
            task = syncTaskDao.findById(taskId);
        } catch (RuntimeException e) {
            log.warn("采集调度启动跳过：读取任务失败 task={} err={}", taskId, e.toString());
            return false;
        }
        if (task.isEmpty() || !ChangeCollector.needsCapture(task.get())) {
            log.debug("采集调度启动跳过：任务状态/模式不需要采集 task={} state={} mode={}", taskId,
                    task.map(t -> t.getState().name()).orElse("MISSING"),
                    task.map(t -> String.valueOf(t.getIncrementalMode())).orElse("null"));
            return false;
        }
        Duration interval = intervalOf(task.get());
        Session session = new Session(interval);
        sessions.put(Long.valueOf(taskId), session);
        arm(taskId, session, interval.toMillis());
        log.info("采集调度已启动 task={} interval={}ms mode={}", taskId, interval.toMillis(),
                task.get().getIncrementalMode());
        return true;
    }

    /**
     * 停止某任务的采集调度（幂等）。
     *
     * @param taskId 任务 ID
     */
    public synchronized void stop(long taskId) {
        Session session = sessions.remove(Long.valueOf(taskId));
        if (session == null) {
            return;
        }
        session.stopped = true;
        ScheduledFuture<?> future = session.future;
        if (future != null) {
            future.cancel(false);
        }
        log.info("采集调度已停止 task={} failures={}", taskId,
                Integer.valueOf(session.consecutiveFailures));
    }

    /**
     * 停止全部采集调度（任务/进程收敛时调用）。
     */
    public synchronized void stopAll() {
        List<Long> taskIds = new ArrayList<>(sessions.keySet());
        for (Long taskId : taskIds) {
            stop(taskId.longValue());
        }
        if (!taskIds.isEmpty()) {
            log.info("采集调度已全部停止 sessions={}", Integer.valueOf(taskIds.size()));
        }
    }

    /**
     * 关停调度线程池（进程退出时调用，之后不再接受 {@link #start(long)}）。
     */
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stopAll();
        scheduler.shutdownNow();
    }

    /**
     * 本实例是否正在为该任务做采集调度。
     *
     * @param taskId 任务 ID
     * @return true 表示已登记
     */
    public boolean isRunning(long taskId) {
        return sessions.containsKey(Long.valueOf(taskId));
    }

    /**
     * 本实例正在采集的任务 ID（升序，诊断用）。
     *
     * @return 任务 ID 列表
     */
    public List<Long> runningTaskIds() {
        List<Long> ids = new ArrayList<>(sessions.keySet());
        Collections.sort(ids);
        return ids;
    }

    /**
     * 同步执行一轮（<b>不排下一次</b>）：供单测、运维手动补偿、以及调度线程调用。
     *
     * <p><b>不抛异常</b>：采集失败是常态（源库抖动），失败信息体现在返回值的
     * {@link RoundResult#failed()} 与 {@link RoundResult#nextDelay()} 上。
     * 若向上抛，调度线程会被 ScheduledExecutorService 静默吞掉并<b>停止再调度</b>
     * （{@code schedule} 派生的任务抛异常后不会自动重排），采集就永久停了。
     *
     * @param taskId 任务 ID
     * @return 本轮结果
     */
    public RoundResult runRound(long taskId) {
        Session session = sessions.get(Long.valueOf(taskId));
        if (session != null && !session.inFlight.compareAndSet(false, true)) {
            log.debug("上一轮采集未结束，本轮跳过 task={}", taskId);
            // 跳过时仍用"上一轮算出来的等待时长"，不能返回 0/1ms ——
            // 那会让调度线程立刻再来一次，退化成忙等。
            return RoundResult.skipped(session.nextDelayMs);
        }
        try {
            int enqueued = collector.collectOnce(taskId);
            Duration next = intervalOfCached(taskId);
            if (session != null) {
                session.consecutiveFailures = 0;
                session.lastError = null;
                session.nextDelayMs = next.toMillis();
            }
            return new RoundResult(false, enqueued, null, next);
        } catch (RuntimeException e) {
            int failures = 1;
            if (session != null) {
                session.consecutiveFailures++;
                failures = session.consecutiveFailures;
                session.lastError = summarize(e);
            }
            Duration next = CaptureBackoff.delayAfter(failures);
            if (session != null) {
                session.nextDelayMs = next.toMillis();
            }
            if (CaptureBackoff.shouldWarn(failures, WARN_SAMPLE_EVERY)) {
                log.warn("增量采集失败（第 {} 次）task={} 下次退避 {}s err={}", failures, taskId,
                        Long.valueOf(next.toSeconds()), summarize(e), e);
            } else {
                log.debug("增量采集失败（第 {} 次）task={} 下次退避 {}s err={}", failures, taskId,
                        Long.valueOf(next.toSeconds()), summarize(e));
            }
            return new RoundResult(false, 0, summarize(e), next);
        } finally {
            if (session != null) {
                session.lastAttemptAt = Instant.now();
                session.inFlight.set(false);
            }
        }
    }

    /**
     * 当前连续失败次数（诊断/测试）。
     *
     * @param taskId 任务 ID
     * @return 次数；未登记返回 0
     */
    public int consecutiveFailures(long taskId) {
        Session session = sessions.get(Long.valueOf(taskId));
        return session == null ? 0 : session.consecutiveFailures;
    }

    /**
     * 下一次采集的等待时长（毫秒；诊断/测试）。
     *
     * @param taskId 任务 ID
     * @return 毫秒；未登记返回全局默认周期
     */
    public long nextDelayMillis(long taskId) {
        Session session = sessions.get(Long.valueOf(taskId));
        return session == null ? defaultPollInterval.toMillis() : session.nextDelayMs;
    }

    /**
     * 最近一次失败摘要（诊断）。
     *
     * @param taskId 任务 ID
     * @return 摘要；无失败返回 null
     */
    public String lastError(long taskId) {
        Session session = sessions.get(Long.valueOf(taskId));
        return session == null ? null : session.lastError;
    }

    /**
     * 某任务实际使用的采集周期（任务级优先，其次全局默认）。
     *
     * @param taskId 任务 ID
     * @return 周期
     */
    public Duration pollIntervalOf(long taskId) {
        try {
            return syncTaskDao.findById(taskId).map(this::intervalOf)
                    .orElse(defaultPollInterval);
        } catch (RuntimeException e) {
            log.debug("读取采集周期失败，回落全局默认 task={} err={}", taskId, e.toString());
            return defaultPollInterval;
        }
    }

    /**
     * 任务配置 → 采集周期。
     *
     * @param task 任务
     * @return 周期（任务级 {@code >0} 时优先）
     */
    Duration intervalOf(SyncTask task) {
        int configured = task == null ? 0 : task.getPollIntervalMs();
        if (configured <= 0) {
            return defaultPollInterval;
        }
        return Duration.ofMillis(configured);
    }

    /**
     * 取（可能已不存在的）会话的周期。
     *
     * @param taskId 任务 ID
     * @return 周期
     */
    private Duration intervalOfCached(long taskId) {
        Session session = sessions.get(Long.valueOf(taskId));
        return session == null ? defaultPollInterval : session.interval;
    }

    /**
     * 排下一次（跑完再排，间隔可动态变化）。
     *
     * @param taskId  任务 ID
     * @param session 会话
     * @param delayMs 延迟毫秒
     */
    private void arm(long taskId, Session session, long delayMs) {
        if (session.stopped || closed.get()) {
            return;
        }
        try {
            session.future = scheduler.schedule(() -> {
                try {
                    RoundResult result = runRound(taskId);
                    arm(taskId, session, result.nextDelay().toMillis());
                } catch (RuntimeException e) {
                    // 兜底：runRound 已收敛异常，这里只防御"再调度"本身出错
                    log.error("采集调度重排失败 task={} err={}", taskId, e.toString(), e);
                    arm(taskId, session, CaptureBackoff.CAP.toMillis());
                }
            }, Math.max(1L, delayMs), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            log.warn("采集调度被拒绝（线程池已关闭）task={}", taskId);
        }
    }

    /**
     * 异常摘要（单行，避免把整栈塞进日志/视图）。
     *
     * @param e 异常
     * @return 摘要
     */
    private static String summarize(RuntimeException e) {
        String message = e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
        String raw = e.getClass().getSimpleName() + ": " + message;
        return raw.length() <= 300 ? raw : raw.substring(0, 300);
    }

    /**
     * 守护线程池（守护线程让 JVM 退出不被采集调度拖住）。
     *
     * @return 线程池
     */
    private static ScheduledExecutorService newScheduler() {
        ThreadFactory factory = new ThreadFactory() {

            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable,
                        "cdc-capture-" + seq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        return Executors.newScheduledThreadPool(SCHEDULER_THREADS, factory);
    }

    /**
     * 一轮采集的结果（不可变）。
     *
     * @param skipped  是否因"上一轮未结束"被跳过
     * @param enqueued 本轮入队条数
     * @param error    失败摘要（成功为 null）
     * @param nextDelay 下一次采集的等待时长
     */
    public record RoundResult(boolean skipped, int enqueued, String error, Duration nextDelay) {

        /**
         * 跳过结果。
         *
         * @param nextDelayMs 下一次等待毫秒
         * @return 结果
         */
        static RoundResult skipped(long nextDelayMs) {
            return new RoundResult(true, 0, null, Duration.ofMillis(Math.max(1L, nextDelayMs)));
        }

        /**
         * @return true 表示本轮成功（无异常）
         */
        public boolean succeeded() {
            return error == null;
        }
    }

    /**
     * 单任务的调度会话。
     */
    private static final class Session {

        /** 正常周期。 */
        private final Duration interval;

        /** 本轮是否在跑（重入保护）。 */
        private final AtomicBoolean inFlight = new AtomicBoolean(false);

        /** 已排的下一次（停止时 cancel）。 */
        private volatile ScheduledFuture<?> future;

        /** 停止标记（防止 stop 与重排竞态）。 */
        private volatile boolean stopped;

        /** 连续失败次数。 */
        private volatile int consecutiveFailures;

        /** 下次等待毫秒。 */
        private volatile long nextDelayMs;

        /** 最近一次失败摘要。 */
        private volatile String lastError;

        /** 最近一次尝试时间（诊断）。 */
        private volatile Instant lastAttemptAt;

        /**
         * @param interval 正常周期
         */
        private Session(Duration interval) {
            this.interval = interval;
            this.nextDelayMs = interval.toMillis();
        }
    }
}
