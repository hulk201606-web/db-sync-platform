package com.dbsync.cdc.sdk;

/**
 * 双写 SDK 的一致性模式（PRD CDC-01）。
 *
 * <p><b>为什么要有两种模式</b>：业务方对"目标库写失败"的容忍度天然不同——
 * 订单类业务宁可整体失败也不接受"源库写了、目标库没写"，而报表/日志类业务
 * 宁可丢一次双写也不愿因旁路系统抖动而回滚主交易。把选择权交给业务方
 * （构造 {@link DbSyncSdkWriter} 时显式传入），平台不替业务方做这个取舍。
 *
 * <ul>
 *   <li>{@link #ASYNC}（默认，也是历史行为）：<b>任何</b>异常都不抛给业务方。
 *       重试耗尽后落本地补偿表 {@code sync_sdk_outbox} + 计数 + 采样 {@code log.error}，
 *       方法正常返回 {@link WriteOutcome#COMPENSATED}。
 *       代价是"业务提交成功但目标库暂时不一致"，由补偿重放 + 存量比对兜底。</li>
 *   <li>{@link #STRICT}：重试耗尽后抛 {@link SdkWriteException}（含任务 ID、表名、主键、根因），
 *       由业务方自行决定回滚整个事务。<b>注意</b>：本 SDK 不支持跨库 XA，
 *       STRICT 只能保证"业务感知到失败"，不能保证"目标库没写进去"（见
 *       {@link DbSyncWriter} 的类注释）。</li>
 * </ul>
 */
public enum SdkWriteMode {

    /** 异步补偿模式（默认）：失败不阻断业务。 */
    ASYNC,

    /** 严格模式：失败抛 {@link SdkWriteException}，由业务方回滚。 */
    STRICT;

    /**
     * 默认模式（{@link #ASYNC}）。
     *
     * <p>默认值选 ASYNC 的理由：SDK 的存在意义是"旁路跟随"，若默认就抛异常，
     * 一旦目标库抖动会直接把业务主链路打挂，这与"迁移期增量跟随"的定位相悖。
     *
     * @return 默认模式
     */
    public static SdkWriteMode defaultMode() {
        return ASYNC;
    }
}
