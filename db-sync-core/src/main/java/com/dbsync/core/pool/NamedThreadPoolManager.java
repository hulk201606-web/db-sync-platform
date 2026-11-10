package com.dbsync.core.pool;

import com.dbsync.common.util.ThreadPoolFactory;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务级线程池管理器。
 *
 * <p>为什么按 taskId 分池而不是全局共享一个大池：任务之间要能"公平隔离"——
 * 一个 8 亿行任务的 8 个迁移线程不能把另一个小任务的调度线程饿死。
 *
 * <p>线程命名规则（架构 §13.1 可观测性要求）：
 * 迁移线程 {@code task-{id}-w{n}}、CDC 线程 {@code cdc-{id}-g{n}}，
 * 这样 jstack 一眼能看出"哪个任务的哪个 worker 卡住"。
 */
public class NamedThreadPoolManager {

    private static final Logger log = LoggerFactory.getLogger(NamedThreadPoolManager.class);

    /** 单任务最大并发上限（防止配置笔误把机器打爆）。 */
    private static final int MAX_CONCURRENCY = 64;

    private final Map<Long, ExecutorService> migrationPools =
            new ConcurrentHashMap<Long, ExecutorService>();
    private final Map<Long, ExecutorService> cdcPools =
            new ConcurrentHashMap<Long, ExecutorService>();

    /**
     * 取（或建）迁移线程池。
     *
     * @param taskId      任务 ID
     * @param concurrency 并发度
     * @return 线程池
     */
    public ExecutorService migrationPool(long taskId, int concurrency) {
        return migrationPools.computeIfAbsent(Long.valueOf(taskId),
                id -> ThreadPoolFactory.newFixed("task-" + id + "-w", clamp(concurrency)));
    }

    /**
     * 取（或建）CDC 线程池。
     *
     * @param taskId      任务 ID
     * @param concurrency 并发度
     * @return 线程池
     */
    public ExecutorService cdcPool(long taskId, int concurrency) {
        return cdcPools.computeIfAbsent(Long.valueOf(taskId),
                id -> ThreadPoolFactory.newFixed("cdc-" + id + "-g", clamp(concurrency)));
    }

    /**
     * 优雅停机单个任务的两个池。
     *
     * @param taskId 任务 ID
     * @param await  等待时长
     */
    public void shutdown(long taskId, Duration await) {
        shutdownOne(migrationPools.remove(Long.valueOf(taskId)), await, "migration", taskId);
        shutdownOne(cdcPools.remove(Long.valueOf(taskId)), await, "cdc", taskId);
    }

    /**
     * 摘除并停机单任务的 CDC 池（<b>不动迁移池</b>）。
     *
     * <p>// ADDED: {@link #cdcPool(long, int)} 是 {@code computeIfAbsent} 缓存，
     * 而 {@code ChangeConsumer.stop} 只把池 {@code shutdown} 掉、不摘除缓存条目。
     * 若不清掉，任务"暂停→恢复"时会拿到一个<b>已终止</b>的池，
     * 新会话提交槽位直接被拒（{@code RejectedExecutionException}），表现为"恢复后不消费"。
     *
     * <p>为什么不用 {@link #shutdown(long, Duration)}：那个方法会把迁移池一起停掉，
     * 暂停增量时误杀存量迁移线程是不可接受的。
     *
     * @param taskId 任务 ID
     */
    public void evictCdcPool(long taskId) {
        ExecutorService pool = cdcPools.remove(Long.valueOf(taskId));
        if (pool == null) {
            return;
        }
        shutdownOne(pool, Duration.ofSeconds(1L), "cdc", taskId);
    }

    /**
     * 优雅停机全部。
     *
     * @param await 等待时长
     */
    public void shutdownAll(Duration await) {
        for (Map.Entry<Long, ExecutorService> entry : migrationPools.entrySet()) {
            shutdownOne(entry.getValue(), await, "migration", entry.getKey().longValue());
        }
        migrationPools.clear();
        for (Map.Entry<Long, ExecutorService> entry : cdcPools.entrySet()) {
            shutdownOne(entry.getValue(), await, "cdc", entry.getKey().longValue());
        }
        cdcPools.clear();
    }

    /**
     * 当前活跃池数量（监控用）。
     *
     * @return 迁移池数量
     */
    public int migrationPoolCount() {
        return migrationPools.size();
    }

    /**
     * 当前活跃 CDC 池数量。
     *
     * @return 数量
     */
    public int cdcPoolCount() {
        return cdcPools.size();
    }

    /**
     * 停机一个池：先停止接收新任务，再等待，超时强制。
     *
     * @param pool   线程池（可 null）
     * @param await  等待时长
     * @param kind   类型（日志）
     * @param taskId 任务 ID（日志）
     */
    private void shutdownOne(ExecutorService pool, Duration await, String kind, long taskId) {
        if (pool == null) {
            return;
        }
        pool.shutdown();
        Duration wait = await == null || await.isZero() || await.isNegative()
                ? Duration.ofSeconds(20) : await;
        try {
            if (!pool.awaitTermination(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                int dropped = pool.shutdownNow().size();
                log.warn("{} 线程池未在 {}ms 内结束，已强制中断 {} 个任务（taskId={}）",
                        kind, wait.toMillis(), dropped, taskId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
            log.warn("{} 线程池停机等待被中断（taskId={}）", kind, taskId);
        }
    }

    /**
     * 并发度收敛。
     *
     * @param concurrency 原值
     * @return 1..64
     */
    private static int clamp(int concurrency) {
        if (concurrency <= 0) {
            return 1;
        }
        return Math.min(concurrency, MAX_CONCURRENCY);
    }
}
