package com.dbsync.core.shard;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.PkRange;
import com.dbsync.common.model.PrimaryKeyMeta;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.ShardPlanOptions;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T18 验收：分片必须"首尾相接、无重叠、无空洞"，且大数值不溢出。
 */
class ShardPlannerTest {

    private final ShardPlanner planner = new ShardPlanner();

    /**
     * 造一张单列主键表。
     *
     * @param pkType 主键类型名
     * @param hasPk  是否有主键
     * @return 表元数据
     */
    private TableMeta table(String pkType, boolean hasPk) {
        ColumnMeta id = ColumnMeta.builder("id", 0, pkType).jdbcType(java.sql.Types.BIGINT)
                .size(19).nullable(false).build();
        ColumnMeta name = ColumnMeta.builder("name", 1, "VARCHAR").jdbcType(java.sql.Types.VARCHAR)
                .size(64).nullable(true).build();
        PrimaryKeyMeta pk = hasPk ? new PrimaryKeyMeta("pk_id", List.of("id"))
                : PrimaryKeyMeta.NONE;
        return TableMeta.of("orders", "t_order", List.of(id, name), pk);
    }

    @Test
    @DisplayName("should_coverWholeRange_When_numeric：100 万分 10 片，首片无界、末片无界、首尾相接")
    void should_coverWholeRange_When_numeric() {
        ShardPlan plan = planner.plan(table("BIGINT", true), 0L, 1_000_000L, 1_000_000L,
                ShardPlanOptions.of(100_000, 100));
        assertThat(plan.shardCount()).isEqualTo(10);
        List<PkRange> ranges = plan.getRanges();
        assertThat(ranges.get(0).getStart()).isNull();
        assertThat(ranges.get(9).getEnd()).isNull();
        for (int i = 1; i < ranges.size(); i++) {
            assertThat(ranges.get(i).getStart()).isEqualTo(ranges.get(i - 1).getEnd());
        }
        assertThat(plan.getRangeKind()).isEqualTo(RangeKind.NUMERIC);
        assertThat(plan.boundariesStrictlyIncreasing()).isTrue();
    }

    @Test
    @DisplayName("should_notOverflow_When_largeLong：Long.MAX_VALUE-1000 上界不溢出")
    void should_notOverflow_When_largeLong() {
        BigDecimal min = BigDecimal.valueOf(Long.MAX_VALUE - 1000L);
        BigDecimal max = BigDecimal.valueOf(Long.MAX_VALUE);
        ShardPlan plan = planner.plan(table("BIGINT", true), min, max, 1000L,
                ShardPlanOptions.of(100, 100));
        assertThat(plan.shardCount()).isEqualTo(10);
        List<PkRange> ranges = plan.getRanges();
        // 所有内部边界必须落在 [min, max] 内且严格递增（溢出会立刻破坏这两条）
        BigDecimal previous = min;
        for (int i = 1; i < ranges.size(); i++) {
            BigDecimal boundary = new BigDecimal(String.valueOf(ranges.get(i).getStart()));
            assertThat(boundary).isGreaterThanOrEqualTo(previous);
            assertThat(boundary).isLessThanOrEqualTo(max);
            previous = boundary;
        }
        assertThat(plan.boundariesStrictlyIncreasing()).isTrue();
    }

    @Test
    @DisplayName("should_degrade_When_noPrimaryKey：单片 + NO_PK 告警")
    void should_degrade_When_noPrimaryKey() {
        ShardPlan plan = planner.plan(table("BIGINT", false), 5_000_000L,
                ShardPlanOptions.defaults());
        assertThat(plan.shardCount()).isEqualTo(1);
        assertThat(plan.hasWarning(ShardPlan.WARN_NO_PK)).isTrue();
        assertThat(plan.getRangeKind()).isEqualTo(RangeKind.NONE);
        assertThat(plan.range(0).getStart()).isNull();
        assertThat(plan.range(0).getEnd()).isNull();
    }

    @Test
    @DisplayName("should_useStringRange_When_stringPk：字符主键按首字符切分，区间类型 STRING")
    void should_useStringRange_When_stringPk() {
        ShardPlan plan = planner.plan(table("VARCHAR", true), "A0001", "Z9999", 1_000_000L,
                ShardPlanOptions.of(100_000, 100));
        assertThat(plan.getRangeKind()).isEqualTo(RangeKind.STRING);
        assertThat(plan.shardCount()).isEqualTo(10);
        assertThat(plan.getRanges().get(0).getStart()).isNull();
        assertThat(plan.getRanges().get(9).getEnd()).isNull();
        // 边界是字符串前缀（不是 LIKE），首个内部边界必须 > "A"
        String firstBoundary = String.valueOf(plan.getRanges().get(1).getStart());
        assertThat(firstBoundary).isGreaterThan("A").isLessThan("Z");
    }

    @Test
    @DisplayName("should_clampShardCount_When_exceedsMax：100 亿行/200 万 → 上限 500 片")
    void should_clampShardCount_When_exceedsMax() {
        ShardPlan plan = planner.plan(table("BIGINT", true), 0L, 10_000_000_000L,
                10_000_000_000L, ShardPlanOptions.of(2_000_000, 500));
        assertThat(plan.shardCount()).isEqualTo(500);
        assertThat(plan.hasWarning(ShardPlan.WARN_CLAMPED_TO_MAX)).isTrue();
    }

    @Test
    @DisplayName("should_useMinShards_When_rowsUnknown：行数未知时用最小分片数")
    void should_useMinShards_When_rowsUnknown() {
        ShardPlan plan = planner.plan(table("BIGINT", true), 0L, ShardPlanOptions.defaults());
        assertThat(plan.shardCount()).isGreaterThanOrEqualTo(1);
        assertThat(plan.hasWarning(ShardPlan.WARN_UNKNOWN_ROWS)).isTrue();
    }

    @Test
    @DisplayName("should_estimateEvenly：估算行数在分片间均分")
    void should_estimateEvenly() {
        ShardPlan plan = planner.plan(table("BIGINT", true), 0L, 1_000_000L, 1_000_000L,
                ShardPlanOptions.of(100_000, 100));
        long[] perShard = plan.estimateRowsPerShard(1_000_000L);
        assertThat(perShard).hasSize(10);
        long sum = 0;
        for (long v : perShard) {
            sum += v;
            assertThat(v).isEqualTo(100_000L);
        }
        assertThat(sum).isEqualTo(1_000_000L);
    }
}
