package com.dbsync.migration;

/**
 * 单个分片的执行结果。
 */
public enum ShardOutcome {

    /** 全部行已提交，分片可置 SUCCESS。 */
    SUCCESS,

    /** 可重试失败（已耗尽重试），分片置 FAILED，等待人工/调度重试。 */
    FAILED,

    /** 毒分片（接管次数超限或不可重试错误），分片置 DEAD 并写死信。 */
    DEAD,

    /** 被暂停/取消或租约丢失而中止，分片状态保持不变（可被其他实例接管）。 */
    ABORTED,
}
