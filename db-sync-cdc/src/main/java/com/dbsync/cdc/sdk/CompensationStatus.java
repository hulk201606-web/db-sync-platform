package com.dbsync.cdc.sdk;

/**
 * 补偿表 {@code sync_sdk_outbox.status} 的取值。
 *
 * <p>三态而非两态的理由：{@link #DISCARDED} 必须与 {@link #REPLAYED} 分开——
 * "重放成功"与"人工判定无需重放（例如该行已被业务删除、或业务确认放弃）"在
 * 对账口径上完全不同，混在一起会让"补偿未完成率"这个指标失去意义。
 */
public enum CompensationStatus {

    /** 待重放（写失败后落库的初始状态）。 */
    PENDING,

    /** 已成功重放（终态）。 */
    REPLAYED,

    /** 已丢弃（人工判定无需重放，需带理由，终态）。 */
    DISCARDED;

    /**
     * 宽松解析（落库字符串 → 枚举，大小写不敏感，未知值按 PENDING 处理）。
     *
     * <p>按 PENDING 兜底是刻意的：宁可多重放一次（目标端 upsert 幂等），
     * 也不要把一条未知状态的记录静默丢掉。
     *
     * @param raw 落库值
     * @return 状态
     */
    public static CompensationStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PENDING;
        }
        String value = raw.trim();
        for (CompensationStatus status : values()) {
            if (status.name().equalsIgnoreCase(value)) {
                return status;
            }
        }
        return PENDING;
    }
}
