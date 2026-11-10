package com.dbsync.common.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池工厂（架构 §13.1：线程名必须是 {@code {role}-{taskId}-{n}}，否则线上排障无法定位）。
 *
 * <p>不用 {@code commonPool}（ForkJoinPool 会被 IO 阻塞拖垮），JDK 17 无虚拟线程时用固定池。
 */
public final class ThreadPoolFactory {

    private ThreadPoolFactory() {
    }

    /**
     * 命名线程工厂。
     *
     * @param prefix 前缀，如 {@code task-1001-w}
     * @return 线程工厂
     */
    public static ThreadFactory namedFactory(String prefix) {
        final AtomicInteger seq = new AtomicInteger(1);
        return runnable -> {
            Thread t = new Thread(runnable, prefix + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * 固定大小线程池（守护线程）。
     *
     * @param prefix 线程名前缀
     * @param size   并发度
     * @return 线程池
     */
    public static ExecutorService newFixed(String prefix, int size) {
        return Executors.newFixedThreadPool(Math.max(1, size), namedFactory(prefix));
    }

    /**
     * 单线程池（用于组内串行消费）。
     *
     * @param name 线程名
     * @return 线程池
     */
    public static ExecutorService newSingle(String name) {
        return Executors.newSingleThreadExecutor(namedFactory(name + "-"));
    }

    /**
     * 定时任务线程池。
     *
     * @param prefix 线程名前缀
     * @param size   线程数
     * @return 调度池
     */
    public static ScheduledExecutorService newScheduled(String prefix, int size) {
        return Executors.newScheduledThreadPool(Math.max(1, size), namedFactory(prefix));
    }
}
