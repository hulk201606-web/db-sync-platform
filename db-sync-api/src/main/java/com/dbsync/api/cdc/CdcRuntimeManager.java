package com.dbsync.api.cdc;

import com.dbsync.api.dto.cdc.CdcRuntimeView;
import com.dbsync.cdc.CdcCaptureScheduler;
import com.dbsync.cdc.ChangeConsumer;
import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.model.SyncTask;
import com.dbsync.core.pool.NamedThreadPoolManager;
import com.dbsync.metadata.dao.SyncTaskDao;
import com.dbsync.metadata.dao.WatermarkDao;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 增量消费的运行期管理器（<b>按 taskId 启停</b>，进程内单例）。
 *
 * <p>它解决的正是本期上线阻断项：{@link ChangeConsumer} 逻辑完整，但全仓库没有任何调用方，
 * 增量变更只堆在 {@code sync_change_log} 里永不落目标库。本类把"任务生命周期"与
 * "消费会话生命周期"对上，并持有唯一的 {@link ChangeConsumer} 实例。
 *
 * <p><b>为什么不做成"单例 Bean 的消费者"就完事</b>：
 * 消费者的会话是按 taskId 组织的线程池 + 组租约，任务暂停/取消后必须停掉，
 * 否则会出现"任务删了线程还在跑、还在往目标库写"。因此本类只把 {@link ChangeConsumer}
 * 做成单例（它是无状态的会话表），而 <b>会话</b>按任务启停。
 *
 * <p><b>多实例安全性</b>：本类不做实例选主。同一任务在 N 个实例上都可能有会话，
 * 但组的归属由 {@code WatermarkStore.acquireAnyGroup} 的 CAS 决定——
 * 抢不到组的槽位空转，抢到的才是真正消费者。这是既有的设计（组独占 + 租约续期），
 * 在这里重复实现选主只会引入两个真相。
 *
 * <p><b>错误处理约定</b>：{@link #start(long)} <b>不抛异常</b>。
 * 增量消费是后台关注点，它的失败不应该让"迁移已启动成功"的接口调用变成 500，
 * 也不应该让应用启动失败；失败信息记 {@code log.error} 并留痕在
 * {@link #status(long)} 的 {@code lastError} 上（只读端点可见）。
 *
 * <p><b>采集侧同源启停（本轮补齐）</b>：增量链路是"源库日志 → {@code sync_change_log}
 * → 目标库"两段。此前本类只管第二段（消费），第一段的采集调度
 * （{@link CdcCaptureScheduler}）没人启停。现在两者由本类<b>成对</b>启停：
 * <ul>
 *   <li>{@link #start(long)}：先起采集（生产），再起消费（搬运）；采集起不来不影响消费；</li>
 *   <li>{@link #stop(long)}：<b>先停采集</b>再停消费——反过来的话，采集会在消费已停之后
 *       继续往 {@code sync_change_log} 里写，任务"暂停"期间队列还在长；</li>
 *   <li>{@link #runningTaskIds()} 返回<b>两者并集</b>：只要有一半在跑，任务就算"本实例在跑"，
 *       对账器才会去收敛它（否则"消费失败但采集还在跑"的会话永远不会被 reconcile 停掉）。</li>
 * </ul>
 */
public class CdcRuntimeManager {

    private static final Logger log = LoggerFactory.getLogger(CdcRuntimeManager.class);

    /** 进程关闭时给全部会话的总预算（含在跑的批次收手）。 */
    private static final Duration SHUTDOWN_BUDGET = Duration.ofSeconds(10L);

    private final NamedThreadPoolManager pools;
    private final ChangeConsumer consumer;
    private final SyncTaskDao syncTaskDao;
    private final WatermarkDao watermarkDao;
    private final CdcCaptureScheduler capture;
    private final String ownerInstance;
    private final Map<Long, RuntimeState> states = new ConcurrentHashMap<>();

    /**
     * 构造（<b>不含采集调度与跨实例视图</b>：只为兼容既有单测的 4 参用法）。
     *
     * @param pools         线程池管理器（提供 task 级 CDC 线程池，并在停止后摘除缓存）
     * @param factory       上下文工厂（生产为 {@link CdcContextFactory}）
     * @param syncTaskDao   任务 DAO（只读：补运行态视图里的配置值）
     * @param ownerInstance 本实例标识
     */
    public CdcRuntimeManager(NamedThreadPoolManager pools, ChangeConsumer.ContextFactory factory,
                             SyncTaskDao syncTaskDao, String ownerInstance) {
        this(pools, factory, syncTaskDao, ownerInstance, null, null);
    }

    /**
     * 构造。
     *
     * @param pools         线程池管理器（提供 task 级 CDC 线程池，并在停止后摘除缓存）
     * @param factory       上下文工厂（生产为 {@link CdcContextFactory}）
     * @param syncTaskDao   任务 DAO（只读：补运行态视图里的配置值）
     * @param ownerInstance 本实例标识
     * @param watermarkDao  水位 DAO（只读：{@code anyInstanceConsuming} 判定；null 时该字段恒 false）
     * @param capture       采集调度器（null 表示不启用采集侧，仅消费）
     */
    public CdcRuntimeManager(NamedThreadPoolManager pools, ChangeConsumer.ContextFactory factory,
                             SyncTaskDao syncTaskDao, String ownerInstance,
                             WatermarkDao watermarkDao, CdcCaptureScheduler capture) {
        this.pools = pools;
        this.syncTaskDao = syncTaskDao;
        this.ownerInstance = ownerInstance == null || ownerInstance.isBlank()
                ? "cdc-local" : ownerInstance;
        this.consumer = new ChangeConsumer(pools, factory);
        this.watermarkDao = watermarkDao;
        this.capture = capture;
    }

    /**
     * 该增量模式是否需要消费者。
     *
     * <p>本期的 {@link IncrementalMode} 只有三种"会产生 {@code sync_change_log} 记录"的模式：
     * {@code TRIGGER_LOG}（触发器写入）、{@code TIMESTAMP_VERSION}（轮询写入）、
     * {@code SDK_WRITE}（业务双写写入），<b>没有 NONE</b>，因此除 null 外都需要消费者。
     * 若将来引入 NONE，只改这一个方法。
     *
     * @param mode 增量模式（可 null）
     * @return true 表示需要消费者
     */
    public static boolean needsConsumer(IncrementalMode mode) {
        return mode != null;
    }

    /**
     * 启动任务消费（幂等：已在跑只记日志）。
     *
     * <p>失败不抛出：记 {@code log.error} 并把摘要写进运行态 {@code lastError}，
     * 不影响后续再次调用（例如运维修好目标库后 resume 一次即可）。
     *
     * <p><b>采集先行</b>：先起采集调度（源库日志 → 队列），再起消费（队列 → 目标库）。
     * 采集起不来不影响消费——{@code sync_change_log} 里可能已有积压需要继续搬运；
     * 消费起不来也不回滚采集——队列本来就是为"目标端暂时不可用"设计的缓冲。
     *
     * @param taskId 任务 ID
     */
    public synchronized void start(long taskId) {
        RuntimeState state = stateOf(taskId);
        startCapture(taskId);
        if (consumer.isRunning(taskId)) {
            log.info("增量消费已在本实例运行，忽略重复启动 task={}", taskId);
            return;
        }
        try {
            consumer.start(taskId);
            state.startedAt = Instant.now();
            state.lastError = null;
            log.info("增量消费已启动 task={} owner={}", taskId, ownerInstance);
        } catch (RuntimeException e) {
            state.startedAt = null;
            state.lastError = summarize(e);
            log.error("增量消费启动失败 task={} owner={} lastError={}", taskId, ownerInstance,
                    state.lastError, e);
        }
    }

    /**
     * 启动采集调度（幂等；不需要采集的模式会被调度器自身跳过）。
     *
     * @param taskId 任务 ID
     */
    private void startCapture(long taskId) {
        if (capture == null) {
            return;
        }
        try {
            capture.start(taskId);
        } catch (RuntimeException e) {
            // 采集失败同样不能影响消费启动：它有自己的退避，见 CdcCaptureScheduler
            log.error("采集调度启动失败 task={} err={}", taskId, e.toString(), e);
        }
    }

    /**
     * 停止任务消费（幂等：没在跑直接返回）。
     *
     * <p>内部最多等待 10 秒（等在手批次收手）。调用方是 pause/cancel 这类
     * "本来就有 10 秒语义"的管理动作，不要放在纯读接口的同步路径上。
     *
     * <p><b>先停采集再停消费</b>：否则采集会在消费已停之后继续往队列里写，
     * "已暂停"的任务队列还在增长（运维看到 backlog 一直涨会误判成消费卡住）。
     *
     * @param taskId 任务 ID
     */
    public synchronized void stop(long taskId) {
        RuntimeState state = states.get(Long.valueOf(taskId));
        boolean running = consumer.isRunning(taskId);
        boolean capturing = capture != null && capture.isRunning(taskId);
        if (!running && !capturing && state == null) {
            return;
        }
        if (capture != null) {
            try {
                capture.stop(taskId);
            } catch (RuntimeException e) {
                log.error("采集调度停止异常（已忽略，线程为守护线程）task={}", taskId, e);
            }
        }
        try {
            consumer.stop(taskId);
        } catch (RuntimeException e) {
            log.error("增量消费停止异常（已忽略，线程池会被回收）task={}", taskId, e);
        } finally {
            // 关键：ChangeConsumer.stop 只 shutdown 池，不摘除线程池缓存；
            // 不摘除的话"暂停→恢复"会拿到已终止的池，恢复后静默不消费。
            pools.evictCdcPool(taskId);
            if (state != null) {
                state.startedAt = null;
            }
        }
        log.info("增量消费已停止 task={}", taskId);
    }

    /**
     * 停止全部会话（进程关闭时调用）。
     *
     * <p>总预算 {@link #SHUTDOWN_BUDGET}：逐个停，超预算就放弃剩余的
     * （剩下的线程池会被 {@code NamedThreadPoolManager.shutdownAll} 兜底），
     * 保证 JVM 关机不被"某个任务卡在长事务里"拖过优雅停机窗口。
     */
    public void stopAll() {
        List<Long> taskIds = new ArrayList<>(states.keySet());
        taskIds.addAll(runningTaskIds());
        long deadline = System.nanoTime() + SHUTDOWN_BUDGET.toNanos();
        int stopped = 0;
        for (Long taskId : taskIds) {
            if (System.nanoTime() >= deadline) {
                log.warn("增量消费停机超出 {}s 预算，剩余会话交由线程池兜底 task={}",
                        SHUTDOWN_BUDGET.toSeconds(), taskId);
                break;
            }
            stop(taskId.longValue());
            stopped++;
        }
        log.info("增量消费已全部停止 sessions={}", Integer.valueOf(stopped));
    }

    /**
     * 本实例是否在消费该任务。
     *
     * @param taskId 任务 ID
     * @return true 表示在跑
     */
    public boolean isRunning(long taskId) {
        return consumer.isRunning(taskId);
    }

    /**
     * 本实例是否在<b>采集</b>该任务（源库日志 → 队列）。
     *
     * <p>与 {@link #isRunning(long)} 分开暴露：两者是不同的故障面，
     * 合并成一个布尔会让"采集停了但消费还在跑（或反之）"变成不可观测状态。
     *
     * @param taskId 任务 ID
     * @return true 表示采集调度已登记
     */
    public boolean isCapturing(long taskId) {
        return capture != null && capture.isRunning(taskId);
    }

    /**
     * 本实例正在跑的任务 ID（消费与采集的<b>并集</b>，升序）。
     *
     * <p>为什么要并集：对账器（{@code CdcRuntimeSupervisor.reconcile}）靠这份清单
     * 找出"库状态已不允许运行"的会话并停掉。若只报消费，则"消费启动失败但采集还在跑"
     * 的任务永远不会被收敛——任务暂停后采集还在往队列里灌数据。
     *
     * @return 任务 ID 列表
     */
    public List<Long> runningTaskIds() {
        TreeSet<Long> ids = new TreeSet<>(consumer.runningTasks());
        if (capture != null) {
            ids.addAll(capture.runningTaskIds());
        }
        return new ArrayList<>(ids);
    }

    /**
     * 运行态视图（供 {@code GET /api/tasks/{taskId}/cdc/runtime}）。
     *
     * @param taskId 任务 ID
     * @return 视图（任务不存在时并发度回 0，不抛异常——端点本身另有存在性校验）
     */
    public CdcRuntimeView status(long taskId) {
        RuntimeState state = states.get(Long.valueOf(taskId));
        boolean running = consumer.isRunning(taskId);
        int concurrency = 0;
        try {
            SyncTask task = syncTaskDao.findById(taskId).orElse(null);
            if (task != null) {
                concurrency = task.getConsumerConcurrency();
            }
        } catch (RuntimeException e) {
            log.debug("读取任务配置失败（运行态视图降级）task={} err={}", taskId, e.toString());
        }
        Instant startedAt = running && state != null ? state.startedAt : null;
        String lastError = state == null ? null : state.lastError;
        return new CdcRuntimeView(taskId, running, anyInstanceConsuming(taskId), concurrency,
                startedAt == null ? null : startedAt.toString(), ownerInstance, lastError);
    }

    /**
     * 集群内是否有任一实例在消费该任务（{@code sync_change_watermark} 上存在未过期租约）。
     *
     * @param taskId 任务 ID
     * @return true 表示有活跃消费者；DAO 未装配或查询失败时为 false（只读视图不因它 500）
     */
    private boolean anyInstanceConsuming(long taskId) {
        if (watermarkDao == null) {
            return false;
        }
        try {
            return watermarkDao.anyLeaseActive(taskId);
        } catch (RuntimeException e) {
            log.debug("读取活跃消费租约失败（视图降级为 false）task={} err={}", taskId, e.toString());
            return false;
        }
    }

    /**
     * 进程关闭：停掉全部会话（≤10s）并关停采集调度线程池。
     */
    @PreDestroy
    public void shutdown() {
        try {
            stopAll();
        } catch (RuntimeException e) {
            log.warn("增量消费停机异常（已忽略）: {}", e.toString());
        }
        if (capture != null) {
            try {
                capture.close();
            } catch (RuntimeException e) {
                log.warn("采集调度关停异常（已忽略）: {}", e.toString());
            }
        }
    }

    /**
     * 取（或建）运行态记录。
     *
     * @param taskId 任务 ID
     * @return 记录
     */
    private RuntimeState stateOf(long taskId) {
        return states.computeIfAbsent(Long.valueOf(taskId), id -> new RuntimeState());
    }

    /**
     * 异常摘要（只留一行，避免把整栈塞进视图）。
     *
     * @param e 异常
     * @return 摘要
     */
    private static String summarize(RuntimeException e) {
        String message = e.getMessage();
        String head = message == null || message.isBlank()
                ? e.getClass().getSimpleName() : message;
        String raw = e.getClass().getSimpleName() + ": " + head;
        return raw.length() <= 300 ? raw : raw.substring(0, 300);
    }

    /**
     * 单任务的运行态（只在本实例有意义）。
     *
     * <p>是否"正在跑"的唯一真相是 {@link ChangeConsumer#isRunning}（它看线程池是否已终止），
     * 这里只留两个<b>消费者自己不记录</b>的信息：启动时刻与失败摘要。
     */
    private static final class RuntimeState {

        /** 本次启动时间。 */
        private volatile Instant startedAt;

        /** 最近一次启动失败摘要（成功后清空）。 */
        private volatile String lastError;
    }
}
