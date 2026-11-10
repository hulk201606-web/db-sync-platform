package com.dbsync.cdc;

import com.dbsync.core.pool.NamedThreadPoolManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 增量消费者管理器：为一个任务起"消费槽位"，每个槽位领一个空闲组并串行消费到底。
 *
 * <p><b>为什么是"槽位领组"而不是"一组一线程"</b>：
 * 组数默认 16，消费并发默认 8。若一组一线程，要么把线程池开到 16（超出配置语义），
 * 要么让 8 个组永远拿不到线程（饥饿）。槽位模型下，一个线程消费完一组后
 * 立刻领下一个空闲组，配置的并发度就是真实的并发上限。
 *
 * <p><b>为什么槽位之间不会抢同一组</b>：{@link WatermarkStore#acquireAnyGroup} 是 CAS，
 * 两个槽位同时领同一组只有一个成功；失败的槽位会去试下一个空闲组。
 */
public class ChangeConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChangeConsumer.class);

    /** 停止时等待在跑组收手的上限。 */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10L);

    /**
     * 上下文工厂（按任务装配 {@link CdcContext}）。
     *
     * <p>// ADDED: 04 文档只给了 {@code ChangeConsumer.start/stop}，未说明上下文从哪来。
     * 用工厂而不是在构造时传入实体，是因为 {@code start(taskId)} 时才需要读任务配置与表结构。
     */
    @FunctionalInterface
    public interface ContextFactory {

        /**
         * 装配上下文。
         *
         * @param taskId     任务 ID
         * @param stopSignal 停止信号
         * @return 上下文
         */
        CdcContext create(long taskId, java.util.function.BooleanSupplier stopSignal);
    }

    private final NamedThreadPoolManager pools;
    private final ContextFactory contextFactory;
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();

    /**
     * 构造。
     *
     * @param pools          线程池管理器
     * @param contextFactory 上下文工厂
     */
    public ChangeConsumer(NamedThreadPoolManager pools, ContextFactory contextFactory) {
        this.pools = pools;
        this.contextFactory = contextFactory;
    }

    /**
     * 启动消费。
     *
     * @param taskId 任务 ID
     */
    public void start(long taskId) {
        if (isRunning(taskId)) {
            log.info("增量消费已在本实例运行，忽略重复启动 task={}", taskId);
            return;
        }
        Session session = new Session();
        CdcContext ctx = contextFactory.create(taskId, session.stop::get);
        ctx.getWatermarks().initializeGroups(taskId, ctx.getGroupCount());
        ExecutorService pool = pools.cdcPool(taskId, ctx.getConsumerConcurrency());
        session.pool = pool;
        int slots = ctx.getConsumerConcurrency();
        for (int i = 0; i < slots; i++) {
            slot(ctx, pool, session, i);
        }
        sessions.put(Long.valueOf(taskId), session);
        log.info("增量消费已启动 task={} concurrency={} owner={}", taskId, slots, ctx.getOwner());
    }

    /**
     * 停止消费（等在跑组收手，上限 10s）。
     *
     * @param taskId 任务 ID
     */
    public void stop(long taskId) {
        Session session = sessions.remove(Long.valueOf(taskId));
        if (session == null) {
            return;
        }
        session.stop.set(true);
        if (session.pool != null) {
            session.pool.shutdown();
            try {
                if (!session.pool.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn("增量消费 {}s 内未全部退出，已强制中断 task={}", STOP_TIMEOUT.toSeconds(),
                            taskId);
                    session.pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                session.pool.shutdownNow();
            }
        }
        log.info("增量消费已停止 task={}", taskId);
    }

    /**
     * 是否在跑。
     *
     * @param taskId 任务 ID
     * @return true 表示在跑
     */
    public boolean isRunning(long taskId) {
        Session session = sessions.get(Long.valueOf(taskId));
        return session != null && session.pool != null && !session.pool.isTerminated();
    }

    /** @return 正在跑的任务 ID 列表（诊断用） */
    public List<Long> runningTasks() {
        List<Long> list = new ArrayList<>();
        for (Map.Entry<Long, Session> entry : sessions.entrySet()) {
            ExecutorService pool = entry.getValue().pool;
            if (pool != null && !pool.isTerminated()) {
                list.add(entry.getKey());
            }
        }
        return list;
    }

    /**
     * 提交一个消费槽位。
     *
     * @param ctx     上下文
     * @param pool    线程池
     * @param session 会话
     * @param index   槽位序号
     */
    private void slot(CdcContext ctx, ExecutorService pool, Session session, int index) {
        // FIXED: 抢组/续租/释放必须用同一个 owner 字符串（带槽位后缀）。
        // 之前续租用的是 ctx.getOwner()（不带后缀），renew 的 consumer_owner=? 永不匹配，
        // 组消费循环一进入就"租约丢失"退出，变更一条都应用不了。
        final String slotOwner = ctx.getOwner() + "#" + index;
        pool.submit(() -> {
            final long taskId = ctx.getTaskId();
            while (!session.stop.get()) {
                Optional<Integer> group;
                try {
                    group = ctx.getWatermarks().acquireAnyGroup(taskId, slotOwner,
                            ctx.getGroupLease());
                } catch (RuntimeException e) {
                    log.error("领取组异常 task={} slot={}", taskId, index, e);
                    sleepQuietly(ctx.getPollInterval());
                    continue;
                }
                if (group.isEmpty()) {
                    sleepQuietly(ctx.getPollInterval());
                    continue;
                }
                int groupNo = group.get().intValue();
                try {
                    new GroupWorker(ctx, groupNo, slotOwner).loop();
                } catch (RuntimeException e) {
                    log.error("组消费异常退出 task={} group={}", taskId, groupNo, e);
                } finally {
                    // 正常收手（暂停/租约丢失）时交还组，让别的槽位能立刻接手
                    ctx.getWatermarks().releaseGroup(taskId, groupNo, slotOwner);
                }
            }
        });
    }

    /**
     * 可中断休眠。
     *
     * @param duration 时长
     */
    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(Math.max(1L, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 本实例内的消费会话。
     */
    private static final class Session {

        /** 停止信号。 */
        private final AtomicBoolean stop = new AtomicBoolean(false);

        /** 线程池。 */
        private volatile ExecutorService pool;
    }
}
