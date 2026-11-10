package com.dbsync.core.port;

import java.time.Duration;
import java.time.Instant;

/**
 * 增量指标端口（架构 §3.1：cutover 的 Gate 需要增量延迟/失败率，但不能依赖 cdc 模块）。
 *
 * <p>实现方：{@code db-sync-cdc} 的 {@code WatermarkStore} 适配器。
 *
 * <p><b>约定</b>：所有方法必须"永不抛异常"，取不到数据时返回 0 / null，
 * 由 Gate 判定为"无数据即不通过"，不能因为指标查询失败导致切流流程崩溃。
 */
public interface IncrementalMetricsPort {

    /**
     * 增量延迟 P95（毫秒）。
     *
     * @param taskId 任务 ID
     * @return P95 毫秒；无数据返回 0
     */
    long latencyP95(long taskId);

    /**
     * 积压条数（未 APPLIED 的变更日志数）。
     *
     * @param taskId 任务 ID
     * @return 条数；无数据返回 0
     */
    long backlog(long taskId);

    /**
     * 双写/应用失败率（0.0~1.0）。
     *
     * @param taskId 任务 ID
     * @param window 统计窗口
     * @return 失败率；窗口内无数据返回 0
     */
    double failRate(long taskId, Duration window);

    /**
     * 最近一次成功应用时间。
     *
     * @param taskId 任务 ID
     * @return 时间；null 表示从未应用
     */
    Instant lastApplyAt(long taskId);
}
