package com.dbsync.migration;

import com.dbsync.common.enums.ShardState;
import com.dbsync.common.exception.LeaseLostException;
import com.dbsync.common.model.Shard;
import com.dbsync.core.lease.LeaseHandle;
import com.dbsync.core.lease.ShardLeaseManager;
import com.dbsync.core.pool.NamedThreadPoolManager;
import com.dbsync.metadata.dao.ShardDao;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 分片调度器：claim → 提交给 worker 池 → 直到"没有可抢占分片 且 没有在跑的分片"。
 *
 * <p><b>为什么调度与执行分离</b>：调度是"抢活"（有竞争、要退避），
 * 执行是"干活"（长时间、要顺序）。合成一个循环会让"抢不到活"的等待
 * 阻塞"正在干活的线程"，并发度直接掉到 1。
 *
 * <p><b>暂停语义（PRD ORC-01）</b>：{@link #stopAccepting()} 只是"不再抢新分片"，
 * <b>不</b>中断在跑的 worker —— 中断会让"正在提交的批次"处于未知状态
 * （事务是否已提交取决于驱动与网络时序）。正确做法是让 worker 读完当前批、
 * 提交、推完断点，然后自己收手。
 */
public class ShardDispatcher implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ShardDispatcher.class);

    /** 抢不到分片时的轮询间隔。 */
    private static final Duration DEFAULT_CLAIM_POLL = Duration.ofMillis(200L);

    /** 停止接收后等待在跑分片收尾的上限（PRD ORC-01：≤10s）。 */
    private static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(10L);

    /**
     * worker 工厂（按分片创建执行器）。
     *
     * <p>// ADDED: 用工厂而不是直接持有 {@code TaskRuntime}，
     * 是为了让单测能注入假 worker 断言调度行为（抢占次数、并发上限）。
     */
    @FunctionalInterface
    public interface WorkerProvider {

        /**
         * 创建执行器。
         *
         * @param shard 分片
         * @return 执行器
         */
        ShardWorker create(Shard shard);
    }

    private final long taskId;
    private final String owner;
    private final int concurrency;
    private final ShardLeaseManager leaseManager;
    private final ShardDao shardDao;
    private final NamedThreadPoolManager pools;
    private final WorkerProvider workerProvider;
    private final Duration claimPollInterval;
    private final Duration drainTimeout;

    /** 是否继续抢新分片。 */
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    /** 在跑的分片数。 */
    private final AtomicInteger inFlight = new AtomicInteger();

    /** 调度循环已退出。 */
    private final CountDownLatch finished = new CountDownLatch(1);

    /** 每个分片的最终结果（诊断用）。 */
    private final Map<Long, ShardOutcome> outcomes = new ConcurrentHashMap<>();

    /** 调度过程中遇到的致命异常（Engine 据此把任务置 FAILED）。 */
    private volatile Throwable fatal;

    /**
     * 构造。
     *
     * @param taskId         任务 ID
     * @param owner          本实例 ID
     * @param concurrency    并发度
     * @param leaseManager   租约管理器
     * @param shardDao       分片 DAO（判空转用）
     * @param pools          线程池管理器
     * @param workerProvider worker 工厂
     */
    public ShardDispatcher(long taskId, String owner, int concurrency,
                           ShardLeaseManager leaseManager, ShardDao shardDao,
                           NamedThreadPoolManager pools, WorkerProvider workerProvider) {
        this(taskId, owner, concurrency, leaseManager, shardDao, pools, workerProvider,
                DEFAULT_CLAIM_POLL, DEFAULT_DRAIN_TIMEOUT);
    }

    /**
     * 构造（可定制轮询与收尾等待，主要给单测用）。
     *
     * @param taskId           任务 ID
     * @param owner            本实例 ID
     * @param concurrency      并发度
     * @param leaseManager     租约管理器
     * @param shardDao         分片 DAO
     * @param pools            线程池管理器
     * @param workerProvider   worker 工厂
     * @param claimPollInterval 抢不到分片时的轮询间隔
     * @param drainTimeout     停止接收后等待收尾的上限
     */
    public ShardDispatcher(long taskId, String owner, int concurrency,
                           ShardLeaseManager leaseManager, ShardDao shardDao,
                           NamedThreadPoolManager pools, WorkerProvider workerProvider,
                           Duration claimPollInterval, Duration drainTimeout) {
        this.taskId = taskId;
        this.owner = owner;
        this.concurrency = Math.max(1, concurrency);
        this.leaseManager = leaseManager;
        this.shardDao = shardDao;
        this.pools = pools;
        this.workerProvider = workerProvider;
        this.claimPollInterval = claimPollInterval == null || claimPollInterval.isNegative()
                ? DEFAULT_CLAIM_POLL : claimPollInterval;
        this.drainTimeout = drainTimeout == null || drainTimeout.isNegative()
                ? DEFAULT_DRAIN_TIMEOUT : drainTimeout;
    }

    /**
     * 调度主循环（阻塞直到"无活可抢"或收到停止信号）。
     */
    public void dispatchLoop() {
        ExecutorService pool = pools.migrationPool(taskId, concurrency);
        try {
            while (accepting.get()) {
                Optional<LeaseHandle> claimed;
                try {
                    claimed = leaseManager.claim(taskId, owner);
                } catch (RuntimeException e) {
                    fatal = e;
                    log.error("抢占分片异常，调度器退出 task={}", taskId, e);
                    break;
                }
                if (claimed.isEmpty()) {
                    if (inFlight.get() == 0 && !hasActiveWork()) {
                        log.info("任务无剩余可迁移分片，调度结束 task={}", taskId);
                        break;
                    }
                    sleepQuietly(claimPollInterval);
                    continue;
                }
                submit(pool, claimed.get());
            }
            // 停止接收新分片后，等在跑的分片自己收尾（不中断，见类注释）
            drain();
        } finally {
            finished.countDown();
            log.info("调度循环退出 task={} outcomes={} fatal={}", taskId, outcomes.size(),
                    fatal == null ? "none" : fatal.getMessage());
        }
    }

    /**
     * 停止接收新分片（优雅停机 / 暂停）。
     */
    public void stopAccepting() {
        if (accepting.compareAndSet(true, false)) {
            log.info("调度器停止接收新分片 task={} inFlight={}", taskId, inFlight.get());
        }
    }

    /**
     * 是否仍在调度。
     *
     * @return true 表示还未退出
     */
    public boolean isAccepting() {
        return accepting.get();
    }

    @Override
    public void run() {
        dispatchLoop();
    }

    /**
     * 等待调度循环退出。
     *
     * @param timeout 超时
     * @return true 表示已退出
     * @throws InterruptedException 被中断
     */
    public boolean awaitFinished(Duration timeout) throws InterruptedException {
        long millis = timeout == null ? 0L : timeout.toMillis();
        return millis <= 0L ? finished.await(0L, TimeUnit.MILLISECONDS)
                : finished.await(millis, TimeUnit.MILLISECONDS);
    }

    /** @return 在跑的分片数 */
    public int inFlight() {
        return inFlight.get();
    }

    /** @return 每个分片的结果快照 */
    public Map<Long, ShardOutcome> outcomes() {
        return Map.copyOf(outcomes);
    }

    /** @return 调度期致命异常（无则 null） */
    public Throwable fatal() {
        return fatal;
    }

    /**
     * 提交一个分片给 worker 池。
     *
     * @param pool   线程池
     * @param handle 租约句柄
     */
    private void submit(ExecutorService pool, LeaseHandle handle) {
        Shard shard = handle.shard();
        inFlight.incrementAndGet();
        pool.submit(() -> {
            ShardOutcome outcome;
            try {
                ShardWorker worker = workerProvider.create(shard);
                outcome = worker.run(shard, handle);
            } catch (LeaseLostException e) {
                // 租约已归别人：不能置任何状态（会把别人的进度覆盖掉）
                outcome = ShardOutcome.ABORTED;
                log.warn("分片租约丢失，放弃处理 shard={} no={}", shard.getId(), shard.getShardNo());
            } catch (RuntimeException e) {
                outcome = ShardOutcome.FAILED;
                log.error("分片执行异常 shard={} no={}", shard.getId(), shard.getShardNo(), e);
            } finally {
                // release 带 owner 条件，成功/失败路径上 lease_owner 已被清空，这里不会误伤
                handle.close();
            }
            outcomes.put(shard.getId(), outcome);
            inFlight.decrementAndGet();
        });
    }

    /**
     * 等待在跑分片收尾（有上限，超时后记警告——此时分片状态仍是 CLAIMED，
     * 租约到期后会被本实例或其他实例接管，数据不会丢）。
     */
    private void drain() {
        long deadline = System.nanoTime() + drainTimeout.toNanos();
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            sleepQuietly(Duration.ofMillis(20L));
        }
        if (inFlight.get() > 0) {
            log.warn("停止接收后仍有 {} 个分片未收尾 task={}（租约到期后会被重新接管）",
                    inFlight.get(), taskId);
        }
    }

    /**
     * 是否还有活跃分片（PENDING / CLAIMED / RUNNING）。
     *
     * <p>FAILED / PAUSED 不算活跃：这两种状态需要 {@code resume}/{@code recover}
     * 显式重置为 PENDING 后才会被重新抢占。把它算成"活跃"会让调度器永远退不出去。
     *
     * @return true 表示还有活
     */
    private boolean hasActiveWork() {
        Map<ShardState, Integer> counts = shardDao.countByState(taskId);
        int active = count(counts, ShardState.PENDING) + count(counts, ShardState.CLAIMED)
                + count(counts, ShardState.RUNNING);
        return active > 0;
    }

    /**
     * 取状态计数（缺省 0）。
     *
     * @param counts 计数表
     * @param state  状态
     * @return 数量
     */
    private static int count(Map<ShardState, Integer> counts, ShardState state) {
        Integer value = counts.get(state);
        return value == null ? 0 : value;
    }

    /**
     * 可中断休眠（中断即停止接收并退出循环，不吞异常）。
     *
     * @param duration 时长
     */
    private void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(Math.max(1L, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopAccepting();
        }
    }
}
