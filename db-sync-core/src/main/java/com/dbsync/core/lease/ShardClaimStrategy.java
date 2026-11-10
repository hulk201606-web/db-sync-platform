package com.dbsync.core.lease;

import com.dbsync.common.model.Shard;
import java.time.Duration;
import java.util.Optional;

/**
 * 分片抢占策略（架构 §4.2.3 的三条路径）。
 *
 * <p>为什么需要抽象：{@code SKIP LOCKED} 在 MySQL 8 / PG 9.5+ / Oracle 11g+ / DB2 11.5 都有，
 * 但 <b>DM8 部分版本没有</b>，H2 的语法细节也不同。与其"部署时人工试错"，
 * 不如把三条路径抽象出来，运行时按方言能力选择并支持配置覆盖。
 *
 * <p>实现约束：SQL 只能出现在 {@code db-sync-metadata} 的 {@code ShardDao} 里
 * （方言隔离 + 单一真相），本接口的实现只做"选择哪条路径"。
 */
public interface ShardClaimStrategy {

    /**
     * 抢一个分片。
     *
     * @param taskId 任务 ID
     * @param owner  持有者
     * @param lease  租约时长
     * @return 抢到的分片；无可用返回 empty
     */
    Optional<Shard> claimOne(long taskId, String owner, Duration lease);

    /**
     * 策略名（日志/监控用）。
     *
     * @return 名称
     */
    String name();
}
