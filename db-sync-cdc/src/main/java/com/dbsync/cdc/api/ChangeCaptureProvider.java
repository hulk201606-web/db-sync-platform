package com.dbsync.cdc.api;

import com.dbsync.common.enums.IncrementalMode;

/**
 * 变更捕获提供者 SPI（T33，<b>接口预留</b>）。
 *
 * <p><b>为什么需要一个 SPI 而不是直接在引擎里 switch 三种模式</b>：
 * 三种模式的启动/停止语义差异很大（触发器要装 DDL、时间戳要轮询、SDK 要业务方配合），
 * 但它们对上层都只是"启动/停止/健康"。抽成 SPI 后，
 * {@code CdcService} 只按 {@link #mode()} 选实现，新增第四种捕获方式（如 binlog）
 * 不需要改任何已有代码。
 *
 * <p>本期实现：
 * <ul>
 *   <li>{@code TRIGGER_LOG} → 由 {@code TriggerLogPoller} + {@code TriggerInstaller} 组成；</li>
 *   <li>{@code TIMESTAMP} → 由 {@code TimestampPoller} 组成（短板见其类注释）；</li>
 *   <li>{@code SDK} → 由 {@code com.dbsync.cdc.sdk.SingleNodeDbSyncWriter} 承担写入，
 *       本 SPI 的对应实现只负责"标记启用/停用"，不做抓取。</li>
 * </ul>
 *
 * <p>// P1：本接口的实现类由 {@code db-sync-api} 侧装配（需要任务配置与数据源）；
 * 本期 cdc 模块只提供 SPI 与各模式的原子能力，不做"自动选择模式"的编排。
 */
public interface ChangeCaptureProvider {

    /**
     * 本实现支持的增量模式。
     *
     * @return 模式
     */
    IncrementalMode mode();

    /**
     * 为任务启用该模式的捕获。
     *
     * @param taskId 任务 ID
     */
    void start(long taskId);

    /**
     * 停止捕获（不删除已部署的触发器/日志表，删除由 API 的 uninstall 显式触发）。
     *
     * @param taskId 任务 ID
     */
    void stop(long taskId);

    /**
     * 健康检查（延迟过高、触发器丢失、位点停滞等都应返回 false）。
     *
     * @param taskId 任务 ID
     * @return true 表示正常
     */
    boolean isHealthy(long taskId);
}
