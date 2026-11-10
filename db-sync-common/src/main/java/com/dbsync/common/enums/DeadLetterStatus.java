package com.dbsync.common.enums;

/**
 * 死信状态。{@code DEAD} 的变更行永不自动归档（架构 §7.4）。
 */
public enum DeadLetterStatus {
    /** 待处理。 */
    OPEN,
    /** 重放中。 */
    REPLAYING,
    /** 已重放成功。 */
    REPLAYED,
    /** 已丢弃（需理由）。 */
    DISCARDED;

    /**
     * @return true 表示需要 Gate G4 计数（阻断切流）
     */
    public boolean isOpen() {
        return this == OPEN || this == REPLAYING;
    }
}
