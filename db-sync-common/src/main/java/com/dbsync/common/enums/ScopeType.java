package com.dbsync.common.enums;

/**
 * 断点/水位的 scope 类型（统一承载在 {@code sync_checkpoint} 一张表）。
 */
public enum ScopeType {
    /** 存量迁移：分片级断点，scopeNo = shard_no。 */
    SHARD,
    /** 增量：组级水位，scopeNo = group_no。 */
    GROUP,
    /** 轮询器：触发器/时间戳轮询位点，scopeNo = poller 序号。 */
    POLLER
}
