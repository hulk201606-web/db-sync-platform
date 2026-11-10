package com.dbsync.core.throttle;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运行中任务的限速器注册表（架构 §13.5 动态限速的"句柄"）。
 *
 * <p><b>为什么需要它</b>：限速器由 {@code TaskRuntimeFactory} 在启动任务时创建，
 * 只被 {@code TaskRuntime} 与各个 {@code ShardWorker} 持有。API 层要"运行中改限速"，
 * 却拿不到那个正在跑的实例——没有注册表就只能改库里的 {@code rate_limit_rows}
 * 等下次启动生效（="改完不生效"，运维会以为按钮坏了）。注册表把"任务 ID → 限速器实例"
 * 显式登记下来，让 API 改的和 worker 用的是<b>同一个对象</b>。
 *
 * <p><b>线程安全</b>：{@link #acquire} 用 {@link ConcurrentHashMap#computeIfAbsent} 保证
 * 同一 taskId 在并发启动（两个请求同时点"启动"）时只产出<b>一个</b>实例，
 * 否则两个调度线程各限各的，实际速率是两份之和（限速形同虚设）。
 *
 * <p><b>生命周期</b>：任务结束（调度线程退出/取消/异常）必须 {@link #remove(long)}，
 * 否则长期运行实例会把每个跑过的任务都留在内存里（泄漏）。
 * 清理挂在 {@code MigrationEngine#launch} 起的调度线程 {@code finally} 中。
 *
 * <p>本类是纯 POJO，<b>不加</b> Spring 注解：由装配层决定它是单例还是每引擎一个。
 */
public class ThrottleRegistry {

    private static final Logger log = LoggerFactory.getLogger(ThrottleRegistry.class);

    /** 任务 ID → 限速器（只在任务运行期间有值）。 */
    private final Map<Long, Throttle> throttles = new ConcurrentHashMap<>();

    /**
     * 取得任务的限速器：不存在则按给定限速创建并登记，已存在则返回<b>同一个</b>实例。
     *
     * <p>已存在时忽略入参限速（运行中改限速必须走 {@link Throttle#updateLimits}，
     * 否则会悄悄换掉 worker 正在用的对象，热更新失效）。
     *
     * @param taskId         任务 ID
     * @param readRowsPerSec 读限速（行/秒，≤0 不限速；仅首次创建时生效）
     * @param writeRowsPerSec 写限速（行/秒，≤0 不限速；仅首次创建时生效）
     * @return 限速器（非 null）
     */
    public Throttle acquire(long taskId, long readRowsPerSec, long writeRowsPerSec) {
        Long key = Long.valueOf(taskId);
        Throttle throttle = throttles.computeIfAbsent(key,
                id -> new Throttle(readRowsPerSec, writeRowsPerSec));
        log.debug("获取限速器 task={} throttle={} active={}", taskId, throttle,
                throttles.size());
        return throttle;
    }

    /**
     * 查找任务的限速器。
     *
     * @param taskId 任务 ID
     * @return 限速器；任务不在本实例运行时返回 empty
     */
    public Optional<Throttle> find(long taskId) {
        return Optional.ofNullable(throttles.get(Long.valueOf(taskId)));
    }

    /**
     * 注销任务的限速器（任务结束/取消/异常退出时调用，防止内存泄漏）。
     *
     * @param taskId 任务 ID
     */
    public void remove(long taskId) {
        Throttle removed = throttles.remove(Long.valueOf(taskId));
        if (removed != null) {
            log.debug("注销限速器 task={} throttle={} active={}", taskId, removed,
                    throttles.size());
        }
    }

    /**
     * 当前登记的任务数（监控/泄漏排查用）。
     *
     * @return 任务数
     */
    public long activeTaskCount() {
        return throttles.size();
    }
}
