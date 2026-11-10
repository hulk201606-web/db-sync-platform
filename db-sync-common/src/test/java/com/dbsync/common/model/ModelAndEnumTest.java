package com.dbsync.common.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dbsync.common.enums.CutoverState;
import com.dbsync.common.enums.DbType;
import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.options.GateThresholds;
import com.dbsync.common.options.NormalizeOptions;
import com.dbsync.common.util.PkRangeUtil;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 模型 / 枚举 / Options 单测：区间语义、状态机跃迁表、归一化默认值、主键比较。
 */
class ModelAndEnumTest {

    @Test
    @DisplayName("TableMeta 拒绝主键不在列集合中")
    void should_reject_When_pkNotInColumns() {
        List<ColumnMeta> columns = List.of(
                ColumnMeta.builder("ID", 1, "BIGINT").build(),
                ColumnMeta.builder("NAME", 2, "VARCHAR").size(64).build());
        assertThatThrownBy(() -> new TableMeta("s", "T", columns,
                new PrimaryKeyMeta("pk_t", List.of("NOT_EXIST")), List.of(), -1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("主键列不在列集合中");
        TableMeta ok = new TableMeta("s", "T", columns,
                new PrimaryKeyMeta("pk_t", List.of("ID")), List.of(), 100L, "订单");
        assertThat(ok.hasPrimaryKey()).isTrue();
        assertThat(ok.primaryKeyColumns()).hasSize(1);
        assertThat(ok.columnNames()).containsExactly("ID", "NAME");
        assertThat(ok.column("name")).isPresent();
    }

    @Test
    @DisplayName("PkRange 语义为 (start, end]")
    void should_useOpenLeftClosedRight_When_contains() {
        PkRange range = new PkRange(10, 20, RangeKind.NUMERIC);
        assertThat(range.contains(10, PkRangeUtil.NUMERIC_COMPARATOR)).isFalse();
        assertThat(range.contains(11, PkRangeUtil.NUMERIC_COMPARATOR)).isTrue();
        assertThat(range.contains(20, PkRangeUtil.NUMERIC_COMPARATOR)).isTrue();
        assertThat(range.contains(21, PkRangeUtil.NUMERIC_COMPARATOR)).isFalse();
        assertThat(new PkRange(null, 5, RangeKind.NUMERIC)
                .contains(5, PkRangeUtil.NUMERIC_COMPARATOR)).isTrue();
        assertThat(new PkRange(5, null, RangeKind.NUMERIC)
                .contains(5, PkRangeUtil.NUMERIC_COMPARATOR)).isFalse();
        PkRange unbounded = PkRange.unbounded(RangeKind.NONE);
        assertThat(unbounded.isUnbounded()).isTrue();
        assertThat(unbounded.contains(123, PkRangeUtil.NUMERIC_COMPARATOR)).isTrue();
    }

    @Test
    @DisplayName("跨类型主键可比较（Long 与 BigDecimal）")
    void should_compareAcrossNumericTypes_When_pkTypesDiffer() {
        assertThat(PkRangeUtil.compare(1L, new BigDecimal("1.0"))).isZero();
        assertThat(PkRangeUtil.compare(2L, new BigDecimal("1.5"))).isPositive();
        assertThat(PkRangeUtil.compare("ABC", "ABD")).isNegative();
        assertThat(PkRangeUtil.isRegression("100", "90", RangeKind.NUMERIC)).isTrue();
        assertThat(PkRangeUtil.isRegression("100", "110", RangeKind.NUMERIC)).isFalse();
        assertThat(PkRangeUtil.isRegression(null, "1", RangeKind.NUMERIC)).isFalse();
    }

    @Test
    @DisplayName("CommitSequence 先比时间再比 seq")
    void should_orderByTsThenSeq_When_commitSequence() {
        Instant t1 = Instant.parse("2026-11-09T10:00:00Z");
        Instant t2 = Instant.parse("2026-11-09T10:00:01Z");
        List<CommitSequence> list = new java.util.ArrayList<>(List.of(
                new CommitSequence(t2, 1), new CommitSequence(t1, 9), new CommitSequence(t1, 2)));
        java.util.Collections.sort(list);
        assertThat(list.get(0).getSeq()).isEqualTo(2);
        assertThat(list.get(1).getSeq()).isEqualTo(9);
        assertThat(list.get(2).getCommitTs()).isEqualTo(t2);
    }

    @Test
    @DisplayName("CutoverState 跃迁表与架构 §4.5.3 对齐")
    void should_matchTransitionTable_When_cutoverState() {
        assertThat(CutoverState.DRAFT.allowedNext()).containsExactly(CutoverState.PRE_CHECK);
        assertThat(CutoverState.PRE_CHECK.allowedNext())
                .contains(CutoverState.GRAY_READ, CutoverState.PRE_CHECK_FAILED, CutoverState.DRAFT);
        assertThat(CutoverState.PRE_CHECK_FAILED.allowedNext())
                .contains(CutoverState.PRE_CHECK, CutoverState.DRAFT);
        assertThat(CutoverState.GRAY_READ.allowedNext())
                .contains(CutoverState.GRAY_WRITE, CutoverState.PRE_CHECK, CutoverState.ROLLED_BACK);
        assertThat(CutoverState.GRAY_WRITE.allowedNext())
                .contains(CutoverState.FULL_TARGET, CutoverState.GRAY_READ, CutoverState.ROLLED_BACK);
        assertThat(CutoverState.FULL_TARGET.allowedNext()).contains(CutoverState.OBSERVE,
                CutoverState.DONE, CutoverState.GRAY_WRITE, CutoverState.ROLLED_BACK);
        assertThat(CutoverState.OBSERVE.allowedNext()).contains(CutoverState.DONE,
                CutoverState.FULL_TARGET, CutoverState.ROLLED_BACK);
        assertThat(CutoverState.DONE.allowedNext()).isEmpty();
        assertThat(CutoverState.ROLLED_BACK.allowedNext()).isEmpty();

        assertThat(CutoverState.isLegal(CutoverState.DRAFT, CutoverState.PRE_CHECK)).isTrue();
        assertThat(CutoverState.isLegal(CutoverState.DRAFT, CutoverState.GRAY_WRITE)).isFalse();
        assertThat(CutoverState.DONE.isTerminal()).isTrue();
        assertThat(CutoverState.ROLLED_BACK.isRollbackReachableFrom(CutoverState.GRAY_WRITE)).isTrue();
        assertThat(CutoverState.ROLLED_BACK.isRollbackReachableFrom(CutoverState.DONE)).isFalse();
        assertThat(CutoverState.GRAY_WRITE.allowedPrev()).contains(CutoverState.FULL_TARGET);
    }

    @Test
    @DisplayName("DbType.ofJdbcUrl 识别 5 种 URL 并拒绝未知")
    void should_parseJdbcUrl_When_knownPrefix() {
        assertThat(DbType.ofJdbcUrl("jdbc:mysql://h:3306/db")).isEqualTo(DbType.MYSQL);
        assertThat(DbType.ofJdbcUrl("jdbc:oracle:thin:@//h:1521/orcl")).isEqualTo(DbType.ORACLE);
        assertThat(DbType.ofJdbcUrl("jdbc:postgresql://h:5432/db")).isEqualTo(DbType.POSTGRESQL);
        assertThat(DbType.ofJdbcUrl("jdbc:dm://h:5236")).isEqualTo(DbType.DM);
        assertThat(DbType.ofJdbcUrl("jdbc:db2://h:50000/db")).isEqualTo(DbType.DB2);
        assertThatThrownBy(() -> DbType.ofJdbcUrl("jdbc:unknown://h"))
                .isInstanceOf(com.dbsync.common.exception.DbSyncException.class)
                .hasMessageContaining("未知 JDBC URL");
    }

    @Test
    @DisplayName("NormalizeOptions 强制项无 setter，报告含 11 条规则")
    void should_exposeMandatoryRules_When_reportMap() {
        NormalizeOptions opts = NormalizeOptions.defaults();
        assertThat(opts.isColumnOrderByName()).isTrue();
        assertThat(opts.isNullSentinel()).isTrue();
        assertThat(opts.isBinaryAsBase64()).isTrue();
        assertThat(opts.isEmptyAsNull()).isTrue();
        assertThat(opts.getTimePrecision()).isEqualTo(NormalizeOptions.TimePrecision.MICROS);
        Map<String, String> report = opts.toReportMap();
        assertThat(report).hasSize(11);
        assertThat(report.get("COLUMN_ORDER_BY_NAME")).contains("强制");
        assertThat(report.get("EMPTY_AS_NULL")).contains("EMPTY_AS_NULL");
        assertThat(report.get("LOB_TRUNCATE")).contains("65536");
        assertThat(opts.withEmptyAsNull(false).isEmptyAsNull()).isFalse();
        assertThat(opts.withEmptyAsNull(false).toReportMap().get("EMPTY_AS_NULL")).contains("STRICT");
        assertThatThrownBy(() -> NormalizeOptions.class.getDeclaredMethod("setColumnOrderByName",
                boolean.class)).isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    @DisplayName("GateThresholds 默认 0.9999/5000/0.0001/0")
    void should_haveDefaultThresholds_When_defaults() {
        GateThresholds t = GateThresholds.defaults();
        assertThat(t.getComparePassRate()).isEqualTo(0.9999d);
        assertThat(t.getLagP95Ms()).isEqualTo(5000L);
        assertThat(t.getDualWriteFailRate()).isEqualTo(0.0001d);
        assertThat(t.getOpenDeadLetters()).isZero();
        assertThat(t.getCompareResultMaxAgeMinutes()).isEqualTo(360L);
    }

    @Test
    @DisplayName("CompareResult 结论按通过率计算")
    void should_computeVerdict_When_passRate() {
        assertThat(CompareResult.verdictOf(10000, 0, 0.9999d))
                .isEqualTo(com.dbsync.common.enums.CompareVerdict.PASS);
        assertThat(CompareResult.verdictOf(10000, 5, 0.9999d))
                .isEqualTo(com.dbsync.common.enums.CompareVerdict.WARN);
        assertThat(CompareResult.verdictOf(10000, 500, 0.9999d))
                .isEqualTo(com.dbsync.common.enums.CompareVerdict.FAIL);
        CompareResult result = new CompareResult(1, 2, 10, 1, 1000, 3, null, List.of());
        assertThat(result.passRate()).isEqualTo(0.997d);
    }
}
