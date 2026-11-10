package com.dbsync.core.shard;

import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.PkRange;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.ShardPlanOptions;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 分片规划器（<b>纯计算</b>，不持有 Connection，便于单测）。
 *
 * <p>数值主键算法（架构 §4.2 / T18）：
 * <pre>
 * n = ceil(rows / targetShardRows)，再 clamp 到 [minShards, maxShards]
 * b_0 = null（无界）, b_n = null（无界）
 * b_i = min + i × (max − min) / n      （i = 1..n−1，全程 BigDecimal，避免溢出/截断）
 * shard_i 的区间 = (b_i, b_{i+1}]
 * </pre>
 *
 * <p>为什么用 BigDecimal：{@code Long.MAX_VALUE - 1000} 到 {@code Long.MAX_VALUE} 这种边界
 * 用 long 做减法会溢出成负数，分片直接乱序（T18 验收 2）。
 *
 * <p>字符主键：按首字符码点切分（{@code "A" < pk ≤ "B"}），最多 26 个左右自然桶；
 * 首字符相同则退化为单片并给 {@link ShardPlan#WARN_STRING_PK} 告警。
 * // TODO(P1): 支持"<前缀采样 + 递归首字符"的真等分（需要额外的 ORDER BY LIMIT 采样查询）。
 *
 * <p>无主键：单片 {@code (null, null]} + {@link ShardPlan#WARN_NO_PK}。
 */
public class ShardPlanner {

    private static final Logger log = LoggerFactory.getLogger(ShardPlanner.class);

    /** 内部边界保留的小数位数（对 DECIMAL 主键足够，对整型主键在输出时会被规整）。 */
    private static final int BOUNDARY_SCALE = 10;

    /**
     * 生成分片计划（文档签名：只用估算行数，把主键空间当作 {@code [0, rows]} 处理）。
     *
     * <p>当调用方已知真实 min/max 时，请用
     * {@link #plan(TableMeta, Object, Object, long, ShardPlanOptions)} 以获得精确切分。
     *
     * @param table         源表元数据
     * @param estimatedRows 估算行数（≤0 表示未知）
     * @param opts          规划选项
     * @return 分片计划
     */
    public ShardPlan plan(TableMeta table, long estimatedRows, ShardPlanOptions opts) {
        if (estimatedRows <= 0) {
            return plan(table, null, null, estimatedRows, opts);
        }
        return plan(table, BigDecimal.ZERO, BigDecimal.valueOf(estimatedRows), estimatedRows, opts);
    }

    /**
     * 生成分片计划（精确版，ADDED：文档签名无法表达真实 min/max，
     * 但“按 min/max 等分”是 T18 算法正文的要求，故补此重载）。
     *
     * @param table         源表元数据
     * @param min           主键最小值（null 表示未知）
     * @param max           主键最大值（null 表示未知）
     * @param estimatedRows 估算行数（≤0 表示未知）
     * @param opts          规划选项
     * @return 分片计划
     */
    public ShardPlan plan(TableMeta table, Object min, Object max, long estimatedRows,
                          ShardPlanOptions opts) {
        ShardPlanOptions options = opts == null ? ShardPlanOptions.defaults() : opts;
        List<String> warnings = new ArrayList<String>();

        if (!table.hasPrimaryKey()) {
            warnings.add(ShardPlan.WARN_NO_PK);
            log.warn("表 {} 无主键，退化为单分片全表扫描", table.qualifiedName());
            return new ShardPlan(RangeKind.NONE, List.of(new PkRange(null, null, RangeKind.NONE)),
                    warnings);
        }

        RangeKind kind = resolveKind(table);
        int shardCount = decideShardCount(estimatedRows, options, warnings);
        if (estimatedRows <= 0) {
            warnings.add(ShardPlan.WARN_UNKNOWN_ROWS);
        }

        if (kind == RangeKind.STRING) {
            return planString(min, max, shardCount, warnings);
        }
        return planNumeric(min, max, shardCount, warnings);
    }

    /**
     * 主键区间类型判定：首列数值 → NUMERIC，否则 STRING。
     *
     * @param table 表元数据
     * @return 区间类型
     */
    private RangeKind resolveKind(TableMeta table) {
        List<ColumnMeta> pkColumns = table.primaryKeyColumns();
        if (pkColumns.isEmpty()) {
            return RangeKind.NONE;
        }
        String type = pkColumns.get(0).getTypeName() == null ? ""
                : pkColumns.get(0).getTypeName().toUpperCase(java.util.Locale.ROOT);
        boolean numeric = type.contains("INT") || type.contains("NUMBER") || type.contains("DECIMAL")
                || type.contains("NUMERIC") || type.contains("BIGINT") || type.contains("SMALLINT")
                || type.contains("TINYINT") || type.contains("FLOAT") || type.contains("DOUBLE")
                || type.contains("REAL") || type.contains("SERIAL");
        return numeric ? RangeKind.NUMERIC : RangeKind.STRING;
    }

    /**
     * 决定分片数量：{@code ceil(rows/target)} 并 clamp。
     *
     * @param estimatedRows 估算行数
     * @param options       选项
     * @param warnings      告警收集器
     * @return 分片数（≥1）
     */
    private int decideShardCount(long estimatedRows, ShardPlanOptions options,
                                 List<String> warnings) {
        int target = Math.max(1, options.getTargetShardRows());
        int minShards = Math.max(1, options.getMinShards());
        int maxShards = Math.max(minShards, options.getMaxShards());
        if (estimatedRows <= 0) {
            return minShards;
        }
        long byRows = (estimatedRows + target - 1L) / target;
        long clamped = Math.max(minShards, Math.min(byRows, maxShards));
        if (byRows > maxShards) {
            warnings.add(ShardPlan.WARN_CLAMPED_TO_MAX);
            log.info("估算行数 {} 需要 {} 片，已按上限截断为 {} 片（单片行数将超目标）",
                    estimatedRows, byRows, maxShards);
        }
        return (int) clamped;
    }

    /**
     * 数值主键等分。
     *
     * @param min        最小值（可为 null）
     * @param max        最大值（可为 null）
     * @param shardCount 目标片数
     * @param warnings   告警
     * @return 计划
     */
    private ShardPlan planNumeric(Object min, Object max, int shardCount, List<String> warnings) {
        BigDecimal low;
        BigDecimal high;
        try {
            low = min == null ? null : toBigDecimal(min);
            high = max == null ? null : toBigDecimal(max);
        } catch (RuntimeException e) {
            warnings.add(ShardPlan.WARN_UNKNOWN_ROWS);
            log.warn("主键上下界无法转数值，退化为单片：{}", e.getMessage());
            return new ShardPlan(RangeKind.NUMERIC, List.of(new PkRange(null, null, RangeKind.NUMERIC)),
                    warnings);
        }
        if (low == null || high == null || low.compareTo(high) >= 0) {
            // 只有一行或边界未知：单片即可，不制造空分片
            return new ShardPlan(RangeKind.NUMERIC,
                    List.of(new PkRange(null, null, RangeKind.NUMERIC)), warnings);
        }
        BigDecimal span = high.subtract(low);
        List<PkRange> ranges = new ArrayList<PkRange>(shardCount);
        Object start = null;
        for (int i = 1; i <= shardCount; i++) {
            Object end;
            if (i == shardCount) {
                end = null;
            } else {
                BigDecimal step = span.multiply(BigDecimal.valueOf(i))
                        .divide(BigDecimal.valueOf(shardCount), new MathContext(20, RoundingMode.HALF_UP));
                BigDecimal boundary = low.add(step).setScale(BOUNDARY_SCALE, RoundingMode.CEILING)
                        .stripTrailingZeros();
                end = boundary.toPlainString();
            }
            ranges.add(new PkRange(start, end, RangeKind.NUMERIC));
            start = end;
        }
        return new ShardPlan(RangeKind.NUMERIC, ranges, warnings);
    }

    /**
     * 字符主键按首字符码点切分。
     *
     * @param min        最小值（可为 null）
     * @param max        最大值（可为 null）
     * @param shardCount 目标片数
     * @param warnings   告警
     * @return 计划
     */
    private ShardPlan planString(Object min, Object max, int shardCount, List<String> warnings) {
        if (min == null || max == null) {
            warnings.add(ShardPlan.WARN_STRING_PK);
            return new ShardPlan(RangeKind.STRING,
                    List.of(new PkRange(null, null, RangeKind.STRING)), warnings);
        }
        String low = String.valueOf(min);
        String high = String.valueOf(max);
        if (low.isEmpty() || high.isEmpty() || low.compareTo(high) >= 0) {
            return new ShardPlan(RangeKind.STRING,
                    List.of(new PkRange(null, null, RangeKind.STRING)), warnings);
        }
        char firstLow = low.charAt(0);
        char firstHigh = high.charAt(0);
        int bucketsByChar = firstHigh - firstLow;
        if (bucketsByChar < 1) {
            warnings.add(ShardPlan.WARN_STRING_PK);
            return new ShardPlan(RangeKind.STRING,
                    List.of(new PkRange(null, null, RangeKind.STRING)), warnings);
        }
        int buckets = Math.min(shardCount, bucketsByChar);
        if (buckets < shardCount) {
            warnings.add(ShardPlan.WARN_STRING_PK);
        }
        List<PkRange> ranges = new ArrayList<PkRange>(buckets);
        Object start = null;
        for (int i = 1; i <= buckets; i++) {
            Object end;
            if (i == buckets) {
                end = null;
            } else {
                // 边界取"下一个首字符"，保证 (start, end] 相邻且无重叠
                end = String.valueOf((char) (firstLow + (firstHigh - firstLow) * i / buckets));
            }
            ranges.add(new PkRange(start, end, RangeKind.STRING));
            start = end;
        }
        return new ShardPlan(RangeKind.STRING, ranges, warnings);
    }

    /**
     * 宽松转 BigDecimal（含字符串形式）。
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
        return new BigDecimal(String.valueOf(value).trim());
    }
}
