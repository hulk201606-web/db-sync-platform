package com.dbsync.common.enums;

/**
 * 分片抢占策略（架构 §4.2.3 降级方案）。
 */
public enum ClaimStrategy {
    /** 首选：{@code SELECT ... FOR UPDATE SKIP LOCKED}（MySQL 8 / PG 9.5+）。 */
    SKIP_LOCKED,
    /** 退化：先取候选再 {@code FOR UPDATE NOWAIT} 探测（不支持 SKIP LOCKED 的库）。 */
    NOWAIT_PROBE,
    /** 兜底：{@code UPDATE ... WHERE id=? AND version=? AND state IN (...)} 乐观 CAS。 */
    OPTIMISTIC_CAS
}
