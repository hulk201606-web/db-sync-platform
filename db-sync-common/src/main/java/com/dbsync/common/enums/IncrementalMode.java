package com.dbsync.common.enums;

/**
 * 增量捕获模式（架构 §2.2 / §4.3.5）。
 */
public enum IncrementalMode {
    /** 业务应用通过 SDK 在业务事务内双写（接口预留 + 单机实现）。 */
    SDK_WRITE,
    /** 源库触发器写入变更日志表（本期主力模式）。 */
    TRIGGER_LOG,
    /** 基于 update_time + version 的轮询（简化实现，不保证强序）。 */
    TIMESTAMP_VERSION;

    /**
     * @return true 表示需要在源库部署触发器
     */
    public boolean needsTrigger() {
        return this == TRIGGER_LOG;
    }

    /**
     * @return true 表示需要业务侧改代码
     */
    public boolean needsAppChange() {
        return this == SDK_WRITE;
    }
}
