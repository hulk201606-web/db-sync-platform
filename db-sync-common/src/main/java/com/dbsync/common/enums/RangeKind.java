package com.dbsync.common.enums;

/**
 * 主键区间类型，决定分片算法与区间谓词的生成方式。
 */
public enum RangeKind {
    /** 数值型主键（BIGINT / NUMBER / DECIMAL）。 */
    NUMERIC,
    /** 字符串型主键（VARCHAR / CHAR）。 */
    STRING,
    /** 哈希分桶（无单调主键时的退化方案）。 */
    HASH,
    /** 无主键，单片全表。 */
    NONE;

    /**
     * @return true 表示支持"首尾相接"的区间切分
     */
    public boolean isSplittable() {
        return this == NUMERIC || this == STRING;
    }
}
