package com.dbsync.core.shard;

import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.model.PkRange;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 分片计划（纯值对象，不含任何 IO）。
 *
 * <p>区间语义见 {@link PkRange}：{@code (start, end]}，首片 {@code start=null}（无界），
 * 末片 {@code end=null}（无界）。
 * <p><b>不变量</b>（{@link ShardPlanner} 必须保证，测试逐条断言）：
 * <ol>
 *   <li>首尾相接：{@code ranges[i].start == ranges[i-1].end}；</li>
 *   <li>无重叠：由 1 推出（半开半闭区间 + 相邻端点相等）；</li>
 *   <li>无空洞：由 1 推出。</li>
 * </ol>
 */
public final class ShardPlan {

    /** 计划告警：源表无主键，退化为单分片全表扫描。 */
    public static final String WARN_NO_PK = "NO_PK";
    /** 计划告警：字符主键无法等分，按前缀区间切分。 */
    public static final String WARN_STRING_PK = "STRING_PK";
    /** 计划告警：估算行数缺失，按最小分片数切分。 */
    public static final String WARN_UNKNOWN_ROWS = "UNKNOWN_ROWS";
    /** 计划告警：分片数被上限截断，单片行数将超过目标值。 */
    public static final String WARN_CLAMPED_TO_MAX = "CLAMPED_TO_MAX";

    private final RangeKind rangeKind;
    private final List<PkRange> ranges;
    private final List<String> warnings;

    /**
     * 构造。
     *
     * @param rangeKind 区间类型
     * @param ranges    区间列表（顺序即 shard_no）
     * @param warnings  告警编码
     */
    public ShardPlan(RangeKind rangeKind, List<PkRange> ranges, List<String> warnings) {
        this.rangeKind = rangeKind == null ? RangeKind.NUMERIC : rangeKind;
        this.ranges = Collections.unmodifiableList(new ArrayList<PkRange>(ranges));
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(
                warnings == null ? List.<String>of() : warnings));
    }

    /** @return 分片数量 */
    public int shardCount() {
        return ranges.size();
    }

    /** @return 区间类型 */
    public RangeKind getRangeKind() {
        return rangeKind;
    }

    /** @return 区间列表（不可变） */
    public List<PkRange> getRanges() {
        return ranges;
    }

    /** @return 告警编码（不可变） */
    public List<String> getWarnings() {
        return warnings;
    }

    /**
     * 取指定分片区间。
     *
     * @param shardNo 分片号（0 基）
     * @return 区间
     */
    public PkRange range(int shardNo) {
        return ranges.get(shardNo);
    }

    /**
     * 是否含某告警。
     *
     * @param warning 告警编码
     * @return true 含
     */
    public boolean hasWarning(String warning) {
        return warnings.contains(warning);
    }

    /**
     * 各分片估算行数（按边界差值估算；无界端用首/末区间宽度代替）。
     *
     * @param estimatedRows 表估算总行数
     * @return 每片估算行数（与 {@link #shardCount()} 等长）
     */
    public long[] estimateRowsPerShard(long estimatedRows) {
        int n = ranges.size();
        long[] result = new long[n];
        if (n == 0) {
            return result;
        }
        if (n == 1 || estimatedRows <= 0) {
            result[0] = Math.max(0L, estimatedRows);
            return result;
        }
        long base = Math.max(0L, estimatedRows) / n;
        long remainder = Math.max(0L, estimatedRows) % n;
        for (int i = 0; i < n; i++) {
            result[i] = base + (i < remainder ? 1 : 0);
        }
        return result;
    }

    /**
     * 边界是否严格递增（数值型用于自检）。
     *
     * @return true 表示所有边界严格递增
     */
    public boolean boundariesStrictlyIncreasing() {
        if (ranges.size() <= 1 || rangeKind != RangeKind.NUMERIC) {
            return true;
        }
        BigDecimal previous = null;
        for (PkRange range : ranges) {
            if (range.getStart() == null) {
                continue;
            }
            BigDecimal current = toBigDecimal(range.getStart());
            if (previous != null && current.compareTo(previous) <= 0) {
                return false;
            }
            previous = current;
        }
        return true;
    }

    /**
     * 宽松转 BigDecimal。
     *
     * @param value 值
     * @return BigDecimal
     */
    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }
        return new BigDecimal(String.valueOf(value));
    }

    @Override
    public String toString() {
        return "ShardPlan[" + rangeKind + ",shards=" + ranges.size() + ",warnings=" + warnings + "]";
    }
}
