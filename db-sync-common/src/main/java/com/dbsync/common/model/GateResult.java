package com.dbsync.common.model;

import com.dbsync.common.enums.GateItemCode;
import com.dbsync.common.options.GateThresholds;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Gate 判定结果（不可变）。
 *
 * <p><b>核心约束（架构 §4.6.2）</b>：前端只展示 {@code items[].pass}，**不做任何阈值比较**；
 * 阈值只在服务端。这样"绕过卡点"必须改服务端代码，而不是改前端 JS 或改请求参数。
 */
public final class GateResult {

    private final boolean pass;
    private final Instant evaluatedAt;
    private final List<GateItem> items;
    private final List<String> reasons;
    private final GateThresholds thresholdSnapshot;

    /**
     * 构造。
     *
     * @param pass               总体是否通过
     * @param evaluatedAt        判定时间
     * @param items              指标明细
     * @param reasons            阻断原因
     * @param thresholdSnapshot  本次使用的阈值快照
     */
    public GateResult(boolean pass, Instant evaluatedAt, List<GateItem> items,
                      List<String> reasons, GateThresholds thresholdSnapshot) {
        this.pass = pass;
        this.evaluatedAt = evaluatedAt;
        this.items = List.copyOf(items == null ? List.of() : items);
        this.reasons = List.copyOf(reasons == null ? List.of() : reasons);
        this.thresholdSnapshot = thresholdSnapshot;
    }

    /**
     * 由指标明细聚合出结果（pass = 全部 pass）。
     *
     * @param items       指标明细
     * @param thresholds  阈值快照
     * @param evaluatedAt 判定时间
     * @return 结果
     */
    public static GateResult of(List<GateItem> items, GateThresholds thresholds, Instant evaluatedAt) {
        List<String> reasons = new ArrayList<>();
        boolean pass = true;
        for (GateItem item : items) {
            if (!item.isPass()) {
                pass = false;
                reasons.add(item.getCode() + " 不满足：" + item.describe());
            }
        }
        return new GateResult(pass, evaluatedAt, items, reasons, thresholds);
    }

    /** @return 是否通过 */
    public boolean isPass() {
        return pass;
    }

    /** @return 判定时间 */
    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }

    /** @return 不可变指标明细 */
    public List<GateItem> getItems() {
        return items;
    }

    /** @return 不可变阻断原因 */
    public List<String> getReasons() {
        return reasons;
    }

    /** @return 阈值快照 */
    public GateThresholds getThresholdSnapshot() {
        return thresholdSnapshot;
    }

    @Override
    public String toString() {
        return "GateResult[pass=" + pass + ",items=" + items.size() + ",reasons=" + reasons + "]";
    }

    /** 单项指标明细（不可变）。 */
    public static final class GateItem {

        private final GateItemCode code;
        private final double actual;
        private final double threshold;
        private final boolean pass;
        private final String source;
        private final String hint;

        /**
         * 构造。
         *
         * @param code      指标编码
         * @param actual    实测值
         * @param threshold 阈值
         * @param pass      是否满足
         * @param source    取数来源（展示用）
         * @param hint      提示（可 null）
         */
        public GateItem(GateItemCode code, double actual, double threshold, boolean pass,
                        String source, String hint) {
            this.code = code;
            this.actual = actual;
            this.threshold = threshold;
            this.pass = pass;
            this.source = source;
            this.hint = hint;
        }

        /** @return 指标编码 */
        public GateItemCode getCode() {
            return code;
        }

        /** @return 实测值 */
        public double getActual() {
            return actual;
        }

        /** @return 阈值 */
        public double getThreshold() {
            return threshold;
        }

        /** @return 是否满足 */
        public boolean isPass() {
            return pass;
        }

        /** @return 取数来源 */
        public String getSource() {
            return source;
        }

        /** @return 提示 */
        public String getHint() {
            return hint;
        }

        /**
         * @return 便于写进 reasons 的可读描述
         */
        public String describe() {
            return "actual=" + actual + ", threshold=" + threshold
                    + (hint == null ? "" : ", hint=" + hint);
        }

        @Override
        public String toString() {
            return code + "(" + actual + "/" + threshold + "," + (pass ? "PASS" : "BLOCK") + ")";
        }
    }
}
