package com.dbsync.common.enums;

/**
 * 差异类型。
 */
public enum DiffType {
    /** 同主键但列值不同。 */
    VALUE_DIFF,
    /** 仅源端有。 */
    ONLY_IN_SOURCE,
    /** 仅目标端有。 */
    ONLY_IN_TARGET;

    /**
     * @return true 表示该差异需要向目标端回放
     */
    public boolean isRepairableByReplay() {
        return this == ONLY_IN_SOURCE || this == VALUE_DIFF;
    }
}
