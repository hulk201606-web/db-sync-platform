package com.dbsync.common.enums;

/**
 * 死信来源类型。
 */
public enum DeadLetterSourceType {
    /** 存量迁移分片失败。 */
    SHARD,
    /** 增量变更应用失败。 */
    CHANGE,
    /** 比对修复失败。 */
    COMPARE_REPAIR
}
