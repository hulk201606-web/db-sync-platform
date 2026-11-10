package com.dbsync.api.dto.cdc;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 增量消费运行态视图（{@code GET /api/tasks/{taskId}/cdc/runtime}）。
 *
 * <p>字段语义（camelCase，前端直接读）：
 * <ul>
 *   <li>{@code taskId}：任务 ID；</li>
 *   <li>{@code running}：<b>本实例</b>是否有该任务的消费会话在跑。
 *       多实例下"别的实例在跑、本实例没跑"时为 {@code false}——它回答的是
 *       "要不要在本机再起一个消费会话"，不是"整体有没有人在消费"；</li>
 *   <li>{@code anyInstanceConsuming}：<b>整个集群</b>是否有实例在消费，
 *       判定依据是 {@code sync_change_watermark} 上存在未过期的
 *       {@code consumer_owner} 租约。它与 {@code running} <b>语义不同、不可合并</b>：
 *       多实例部署下最常见的排障场景恰恰是"running=false 但 anyInstanceConsuming=true"
 *       （本机没抢到组 / 本机刚重启），此时若只有一个字段，运维会误判成"没人在消费"；</li>
 *   <li>{@code consumerConcurrency}：任务配置的增量消费并发
 *       （来自 {@code sync_task.consumer_concurrency}）；</li>
 *   <li>{@code startedAt}：本实例本次启动消费的时间（ISO-8601；未运行为 null）；</li>
 *   <li>{@code ownerInstance}：本实例标识（消费者 ID，同时也是组租约持有者）；</li>
 *   <li>{@code lastError}：本实例最近一次启动消费失败的摘要（成功后清空；从未失败为 null）。</li>
 * </ul>
 *
 * <p><b>配置变更不会重建会话（已拍板的决策）</b>：
 * {@code consumerConcurrency} / {@code batchSize} / {@code maxRetry} 改完之后，
 * <b>本次会话继续用旧配置</b>，新配置在<b>下一次 resume</b>（{@code stop → start}）时生效；
 * 周期对账（{@code CdcRuntimeSupervisor.reconcile}）<b>不会</b>为了应用新配置而隐式重启会话。
 * 理由：隐式重启会让"改一个并发参数"变成一次不可预期的中断（在跑的批次被丢弃重放），
 * 而重启时机应当由运维显式决定。因此本视图里的 {@code consumerConcurrency} 是
 * <b>库里当前配置</b>，可能与正在跑的会话实际使用的并发不同——这是有意的：
 * 它就是要让运维看到"配置与运行态可能不一致，需要 resume 一次"。
 *
 * <p><b>不自动重启消费者（已拍板的决策）</b>：{@code reconcile()} 发现
 * "RUNNING + 需要消费 + 本实例未运行 + 有 lastError"时只打 {@code log.warn} 并保留
 * {@code lastError} 由本视图暴露，<b>不会</b>每 30s 重试一次。
 * 理由：目标库不可用时，30s 一次的自动重试本身就是新的压力源。
 *
 * @param taskId               任务 ID
 * @param running              本实例是否在消费
 * @param anyInstanceConsuming 集群内是否有任一实例在消费（未过期租约存在）
 * @param consumerConcurrency  配置的消费并发
 * @param startedAt            本实例启动时间（ISO-8601 文本）
 * @param ownerInstance        本实例标识
 * @param lastError            最近一次启动失败摘要
 */
public record CdcRuntimeView(long taskId, boolean running, boolean anyInstanceConsuming,
                             int consumerConcurrency, String startedAt, String ownerInstance,
                             String lastError) {

    /**
     * @return 有序 Map（null 字段保留，便于前端"字段恒在"判断）
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", Long.valueOf(taskId));
        map.put("running", Boolean.valueOf(running));
        map.put("anyInstanceConsuming", Boolean.valueOf(anyInstanceConsuming));
        map.put("consumerConcurrency", Integer.valueOf(consumerConcurrency));
        map.put("startedAt", startedAt);
        map.put("ownerInstance", ownerInstance);
        map.put("lastError", lastError);
        return map;
    }
}
