package com.dbsync.cdc.sdk;

import java.util.List;
import java.util.Map;

/**
 * 业务侧双写 SDK 入口（T33，<b>接口预留</b>）。
 *
 * <p><b>为什么不在这里做分布式事务</b>：源库与目标库是两个独立事务管理器，
 * 真正的跨库原子性需要 XA 或"本地消息表 + 对账补偿"。
 * 本平台的定位是"迁移期增量跟随"，采用的是<b>本地消息表</b>语义：
 * 业务写源库 → 触发器/日志表捕获变更 → 平台异步应用。
 * SDK 的职责只是"把变更写进本地的待应用队列"，因此它<b>不需要</b>与业务事务原子，
 * 最坏情况是"业务提交了但 SDK 没记上"——这种情况由存量迁移 + 比对修复兜底（PRD CDC-09）。
 *
 * <p>// P1：接入分布式事务（XA / Seata / 本地消息表 + 主动对账）不在本期范围。
 * 本接口的方法签名已按"后续可插入事务协调器"设计：所有写操作都是"登记意图"，
 * 而不是"直接写目标库"，因此加上协调器时不需要改业务调用方。
 */
public interface DbSyncWriter {

    /**
     * 登记一行变更（upsert 语义）。
     *
     * @param table 表名（与同步任务里配置的来源表名一致）
     * @param row   行数据（列名 → 值，大小写不敏感）
     * @return 本次登记的变更条数（通常 1）
     */
    long write(String table, Map<String, Object> row);

    /**
     * 登记一次删除。
     *
     * @param table   表名
     * @param pkTuple 主键元组（顺序与同步任务配置的主键列一致）
     * @return 本次登记的变更条数（通常 1）
     */
    long delete(String table, List<Object> pkTuple);

    /**
     * 把缓冲区刷入变更队列。
     */
    void flush();

    /**
     * 健康检查（供业务侧熔断：SDK 不可用时业务应降级为"只写源库"并告警）。
     *
     * @return true 表示可用
     */
    boolean isHealthy();
}
