package com.dbsync.cdc.sdk;

/**
 * 单次双写的结果（供业务方在不使用 STRICT 模式时判定"这条到底有没有落上"）。
 *
 * <p>为什么不用 {@code boolean}：三种情况对业务方的意义完全不同——
 * {@link #APPLIED} 是正常路径；{@link #COMPENSATED} 表示"已经记进本地补偿表，
 * 后续会重放"，业务方可以据此打点/SLA 统计；{@link #DROPPED} 表示连补偿都没写成功
 * （补偿表也不可用），这是必须告警的最坏情况。
 */
public enum WriteOutcome {

    /** 已成功登记（同步路径成功）。 */
    APPLIED,

    /** 同步重试耗尽，但已落本地补偿表 {@code sync_sdk_outbox}（status=PENDING）。 */
    COMPENSATED,

    /** 同步失败且补偿也失败：本条变更<b>只</b>留下日志与计数，需人工介入（PRD CDC-09 兜底）。 */
    DROPPED
}
