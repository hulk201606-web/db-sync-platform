package com.dbsync.common.enums;

/**
 * Gate 四项指标编码（架构 §4.6.1）。阈值只在服务端判定，前端仅展示 {@code pass}。
 */
public enum GateItemCode {
    /** G1 比对通过率 ≥ threshold。 */
    COMPARE_PASS_RATE,
    /** G2 增量延迟 P95 ≤ threshold（ms）。 */
    INCREMENT_LAG_P95,
    /** G3 双写失败率 ≤ threshold。 */
    DUAL_WRITE_FAIL_RATE,
    /** G4 未处理死信数 == threshold（默认 0）。 */
    OPEN_DEAD_LETTERS
}
