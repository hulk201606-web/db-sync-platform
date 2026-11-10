package com.dbsync.common.options;

/**
 * Gate 四阈值（不可变）。默认值必须等于 {@code 0.9999 / 5000 / 0.0001 / 0}（架构 §4.6.1）。
 *
 * <p>阈值变更必须落审计（改了阈值等于改了"能不能切"的门槛，架构 §10 原则 4）。
 */
public final class GateThresholds {

    private final double comparePassRate;
    private final long lagP95Ms;
    private final double dualWriteFailRate;
    private final long openDeadLetters;
    private final long compareResultMaxAgeMinutes;

    /**
     * 构造。
     *
     * @param comparePassRate            比对通过率下限（如 0.9999）
     * @param lagP95Ms                   增量延迟 P95 上限（ms）
     * @param dualWriteFailRate          双写失败率上限
     * @param openDeadLetters            未处理死信数上限（0）
     * @param compareResultMaxAgeMinutes 比对结果有效期（分钟，默认 360 = 6h）
     */
    public GateThresholds(double comparePassRate, long lagP95Ms, double dualWriteFailRate,
                          long openDeadLetters, long compareResultMaxAgeMinutes) {
        this.comparePassRate = comparePassRate;
        this.lagP95Ms = lagP95Ms;
        this.dualWriteFailRate = dualWriteFailRate;
        this.openDeadLetters = openDeadLetters;
        this.compareResultMaxAgeMinutes = compareResultMaxAgeMinutes <= 0
                ? 360 : compareResultMaxAgeMinutes;
    }

    /**
     * @return 默认阈值 0.9999 / 5000 / 0.0001 / 0 / 6h
     */
    public static GateThresholds defaults() {
        return new GateThresholds(0.9999d, 5000L, 0.0001d, 0L, 360L);
    }

    /** @return 比对通过率下限 */
    public double getComparePassRate() {
        return comparePassRate;
    }

    /** @return 延迟 P95 上限 ms */
    public long getLagP95Ms() {
        return lagP95Ms;
    }

    /** @return 双写失败率上限 */
    public double getDualWriteFailRate() {
        return dualWriteFailRate;
    }

    /** @return 死信数上限 */
    public long getOpenDeadLetters() {
        return openDeadLetters;
    }

    /** @return 比对结果有效期（分钟） */
    public long getCompareResultMaxAgeMinutes() {
        return compareResultMaxAgeMinutes;
    }

    @Override
    public String toString() {
        return "GateThresholds[pass≥" + comparePassRate + ",lag≤" + lagP95Ms
                + ",fail≤" + dualWriteFailRate + ",dl=" + openDeadLetters + "]";
    }
}
