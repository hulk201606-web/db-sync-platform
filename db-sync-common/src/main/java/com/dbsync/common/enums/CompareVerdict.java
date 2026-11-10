package com.dbsync.common.enums;

/**
 * 比对结论。
 */
public enum CompareVerdict {
    /** 通过（差异率低于阈值）。 */
    PASS,
    /** 警告（有差异但在容忍范围 / 存在 LOB 截断等 WARN 项）。 */
    WARN,
    /** 不通过。 */
    FAIL;

    /**
     * @return true 表示 Gate G1 可用（PASS 或 WARN）
     */
    public boolean isAcceptableForGate() {
        return this == PASS || this == WARN;
    }
}
