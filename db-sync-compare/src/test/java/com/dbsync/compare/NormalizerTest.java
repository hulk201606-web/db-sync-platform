package com.dbsync.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.options.NormalizeOptions;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T34 验收：归一化 10 条规则（P0~P10）逐条断言，共 24 个用例。
 *
 * <p>本测试是"假阳性治理"的门禁：误报率 ≤ 0.1% 不是靠算法聪明，而是靠这张规则表穷举掉
 * 所有语义等价。任何一条规则被改坏，这里必然红。
 */
class NormalizerTest {

    /** 默认选项（EMPTY_AS_NULL=true / 去尾零 / 微秒 / Asia/Shanghai / 不裁剪 / 不折叠）。 */
    private final Normalizer defaults = new Normalizer(NormalizeOptions.defaults());

    /** 列构造辅助。 */
    private static ColumnMeta col(String name, int jdbcType, String typeName) {
        return ColumnMeta.builder(name, 0, typeName).jdbcType(jdbcType).build();
    }

    private static final ColumnMeta TEXT = col("name", Types.VARCHAR, "VARCHAR");
    private static final ColumnMeta NUMERIC = col("amount", Types.DECIMAL, "DECIMAL");
    private static final ColumnMeta FLOAT_COL = col("rate", Types.DOUBLE, "DOUBLE");
    private static final ColumnMeta TIME_COL = col("update_time", Types.TIMESTAMP, "TIMESTAMP");
    private static final ColumnMeta BIN_COL = col("blob_col", Types.BLOB, "BLOB");

    // ---------------------------------------------------------------- P0

    @Test
    @DisplayName("P0：列顺序不同的两行必须同哈希（否则 Oracle↔PG 全表假差异）")
    void should_sameHash_When_columnOrderDiffers() {
        List<ColumnMeta> cols = List.of(TEXT, NUMERIC);
        Map<String, Object> rowA = new LinkedHashMap<String, Object>();
        rowA.put("name", "a");
        rowA.put("amount", new BigDecimal("1.10"));
        Map<String, Object> rowB = new LinkedHashMap<String, Object>();
        rowB.put("amount", new BigDecimal("1.100"));
        rowB.put("name", "a");

        // 列顺序相反（物理顺序不同），但哈希必须相同
        assertEquals(defaults.hashRow(rowA, cols), defaults.hashRow(rowB, cols));
        assertEquals(defaults.hashRow(rowA, List.of(NUMERIC, TEXT)),
                defaults.hashRow(rowA, List.of(TEXT, NUMERIC)));
    }

    @Test
    @DisplayName("P0：列顺序强制排序，无法通过选项关闭")
    void should_alwaysSortColumns_When_optionHasNoSwitch() {
        assertTrue(defaults.options().isColumnOrderByName());
        assertTrue(defaults.options().isNullSentinel());
    }

    @Test
    @DisplayName("P0：行内多余列不参与哈希（只按 cols 列表计算）")
    void should_ignoreExtraColumns_When_notInColumnList() {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("name", "a");
        row.put("other", "ignored");
        assertEquals(defaults.hashRow(row, List.of(TEXT)),
                defaults.hashRow(Map.of("name", "a"), List.of(TEXT)));
    }

    // ---------------------------------------------------------------- P1

    @Test
    @DisplayName("P1：NULL 与空串必须不同哈希（STRICT 模式）")
    void should_diffHash_When_nullVersusEmptyStrict() {
        Normalizer strict = new Normalizer(NormalizeOptions.defaults().withEmptyAsNull(false));
        String nullHash = strict.hashRow(row("name", null), List.of(TEXT));
        String emptyHash = strict.hashRow(row("name", ""), List.of(TEXT));
        assertNotEquals(nullHash, emptyHash, "NULL 与 '' 混同会导致 Oracle→PG 全线假差异");
    }

    @Test
    @DisplayName("P1：NULL 与普通字符串不同哈希")
    void should_diffHash_When_nullVersusText() {
        assertNotEquals(defaults.hashRow(row("name", null), List.of(TEXT)),
                defaults.hashRow(row("name", "NULL"), List.of(TEXT)),
                "字面量 \"NULL\" 不能与 NULL 混同");
    }

    @Test
    @DisplayName("P1：缺列按 NULL 处理")
    void should_treatMissingColumnAsNull_When_notPresent() {
        assertEquals(defaults.hashRow(row("name", null), List.of(TEXT)),
                defaults.hashRow(new LinkedHashMap<String, Object>(), List.of(TEXT)));
    }

    // ---------------------------------------------------------------- P2

    @Test
    @DisplayName("P2：EMPTY_AS_NULL=true 时空串与 NULL 同哈希")
    void should_sameHash_When_emptyAsNullEnabled() {
        Normalizer lenient = new Normalizer(NormalizeOptions.defaults().withEmptyAsNull(true));
        assertEquals(lenient.hashRow(row("name", null), List.of(TEXT)),
                lenient.hashRow(row("name", ""), List.of(TEXT)));
    }

    @Test
    @DisplayName("P2：EMPTY_AS_NULL 关闭后空串有独立编码，且不是 NULL 哨兵")
    void should_useEmptySentinel_When_strict() {
        Normalizer strict = new Normalizer(NormalizeOptions.defaults().withEmptyAsNull(false));
        assertEquals(NormalizeOptions.EMPTY_SENTINEL, strict.normalize("", TEXT));
        assertEquals(NormalizeOptions.NULL_SENTINEL, strict.normalize(null, TEXT));
    }

    // ---------------------------------------------------------------- P3

    @Test
    @DisplayName("P3：1.10 与 1.1 同哈希；1E+2 与 100 同哈希")
    void should_sameHash_When_decimalTrailingZerosDiffer() {
        assertEquals(defaults.hashRow(row("amount", new BigDecimal("1.10")), List.of(NUMERIC)),
                defaults.hashRow(row("amount", new BigDecimal("1.1")), List.of(NUMERIC)));
        assertEquals(defaults.hashRow(row("amount", new BigDecimal("100")), List.of(NUMERIC)),
                defaults.hashRow(row("amount", new BigDecimal("1E+2")), List.of(NUMERIC)));
    }

    @Test
    @DisplayName("P3：不能把不同数值判成相同（1.10 vs 1.20）")
    void should_diffHash_When_decimalValueDiffers() {
        assertNotEquals(defaults.hashRow(row("amount", new BigDecimal("1.10")), List.of(NUMERIC)),
                defaults.hashRow(row("amount", new BigDecimal("1.20")), List.of(NUMERIC)));
    }

    @Test
    @DisplayName("P3：去尾零关闭后 1.10 与 1.1 不同哈希（保留标度）")
    void should_diffHash_When_trimZerosDisabled() {
        Normalizer raw = new Normalizer(NormalizeOptions.defaults().withDecimalTrimZeros(false));
        assertNotEquals(raw.hashRow(row("amount", new BigDecimal("1.10")), List.of(NUMERIC)),
                raw.hashRow(row("amount", new BigDecimal("1.1")), List.of(NUMERIC)));
    }

    // ---------------------------------------------------------------- P4

    @Test
    @DisplayName("P4：Oracle DATE（Asia/Shanghai 10:00）与 PG timestamptz（UTC 02:00）同哈希")
    void should_sameHash_When_timezoneInterpretationDiffers() {
        Timestamp oracleLike = Timestamp.valueOf(LocalDateTime.of(2024, 11, 9, 10, 0, 0));
        OffsetDateTime pgLike = OffsetDateTime.parse("2024-11-09T02:00:00Z");

        String h1 = defaults.hashRow(row("update_time", oracleLike), List.of(TIME_COL));
        String h2 = defaults.hashRow(row("update_time", pgLike), List.of(TIME_COL));
        assertEquals(h1, h2, "时区没统一会全线差 8 小时");
    }

    @Test
    @DisplayName("P4：源时区配置错误时必须产生不同哈希（不能无条件相等）")
    void should_diffHash_When_sourceTimeZoneDiffers() {
        Timestamp wallClock = Timestamp.valueOf(LocalDateTime.of(2024, 11, 9, 10, 0, 0));
        Normalizer utc = new Normalizer(
                NormalizeOptions.defaults().withSourceTimeZone(ZoneId.of("UTC")));
        assertNotEquals(defaults.hashRow(row("update_time", wallClock), List.of(TIME_COL)),
                utc.hashRow(row("update_time", wallClock), List.of(TIME_COL)));
    }

    @Test
    @DisplayName("P4：时刻型数据（Instant）不随时区配置变化")
    void should_sameHash_When_instantAndConfiguredZoneChanges() {
        Instant instant = Instant.parse("2024-11-09T02:00:00Z");
        Normalizer utc = new Normalizer(
                NormalizeOptions.defaults().withSourceTimeZone(ZoneId.of("UTC")));
        assertEquals(defaults.hashRow(row("update_time", instant), List.of(TIME_COL)),
                utc.hashRow(row("update_time", instant), List.of(TIME_COL)));
    }

    @Test
    @DisplayName("P4：纯日期按源时区零点解释")
    void should_normalizeToStartOfDay_When_localDate() {
        Object normalized = defaults.normalize(java.time.LocalDate.of(2024, 11, 9), TIME_COL);
        assertEquals(Long.valueOf(Instant.parse("2024-11-08T16:00:00Z").getEpochSecond()
                * 1_000_000L), normalized);
    }

    // ---------------------------------------------------------------- P5

    @Test
    @DisplayName("P5：纳秒尾部差异截断到微秒后同哈希")
    void should_sameHash_When_nanoTailTruncatedToMicros() {
        Timestamp a = Timestamp.valueOf(LocalDateTime.of(2024, 11, 9, 10, 0, 0, 123_456_789));
        Timestamp b = Timestamp.valueOf(LocalDateTime.of(2024, 11, 9, 10, 0, 0, 123_456_000));
        assertEquals(defaults.hashRow(row("update_time", a), List.of(TIME_COL)),
                defaults.hashRow(row("update_time", b), List.of(TIME_COL)));
    }

    @Test
    @DisplayName("P5：微秒级真实差异必须报出（截断到秒会漏报）")
    void should_diffHash_When_microsDiffer() {
        Timestamp a = Timestamp.valueOf(LocalDateTime.of(2024, 11, 9, 10, 0, 0, 123_456_000));
        Timestamp b = Timestamp.valueOf(LocalDateTime.of(2024, 11, 9, 10, 0, 0, 123_000_000));
        assertNotEquals(defaults.hashRow(row("update_time", a), List.of(TIME_COL)),
                defaults.hashRow(row("update_time", b), List.of(TIME_COL)));
    }

    @Test
    @DisplayName("P5：切到毫秒精度后微秒差异被折叠（开关生效）")
    void should_collapseMicros_When_precisionIsMillis() {
        Normalizer millis = new Normalizer(NormalizeOptions.defaults()
                .withTimePrecision(NormalizeOptions.TimePrecision.MILLIS));
        Timestamp a = Timestamp.valueOf(LocalDateTime.of(2024, 11, 9, 10, 0, 0, 123_456_000));
        Timestamp b = Timestamp.valueOf(LocalDateTime.of(2024, 11, 9, 10, 0, 0, 123_000_000));
        assertEquals(millis.hashRow(row("update_time", a), List.of(TIME_COL)),
                millis.hashRow(row("update_time", b), List.of(TIME_COL)));
    }

    // ---------------------------------------------------------------- P6/P7

    @Test
    @DisplayName("P6：默认不裁剪空格（空格通常有业务含义）")
    void should_keepWhitespace_When_trimDisabledByDefault() {
        assertNotEquals(defaults.hashRow(row("name", "a "), List.of(TEXT)),
                defaults.hashRow(row("name", "a"), List.of(TEXT)));
    }

    @Test
    @DisplayName("P6：开启裁剪后首尾空格不影响哈希")
    void should_sameHash_When_trimEnabled() {
        Normalizer trim = new Normalizer(NormalizeOptions.defaults().withTrimWhitespace(true));
        assertEquals(trim.hashRow(row("name", " a "), List.of(TEXT)),
                trim.hashRow(row("name", "a"), List.of(TEXT)));
    }

    @Test
    @DisplayName("P7：开启大小写折叠后 ABC 与 abc 同哈希；默认区分")
    void should_foldCase_When_caseInsensitiveEnabled() {
        Normalizer ci = new Normalizer(NormalizeOptions.defaults().withCaseInsensitive(true));
        assertEquals(ci.hashRow(row("name", "ABC"), List.of(TEXT)),
                ci.hashRow(row("name", "abc"), List.of(TEXT)));
        assertNotEquals(defaults.hashRow(row("name", "ABC"), List.of(TEXT)),
                defaults.hashRow(row("name", "abc"), List.of(TEXT)));
    }

    // ---------------------------------------------------------------- P8

    @Test
    @DisplayName("P8：哈希阶段不做浮点容差（容差不可传递），逐列比较才做")
    void should_notUseToleranceInHash_butUseItInColumnCompare() {
        Normalizer withEps = new Normalizer(NormalizeOptions.defaults().withFloatTolerance(1e-6));
        Double a = Double.valueOf(1.0000001d);
        Double b = Double.valueOf(1.0d);
        assertNotEquals(withEps.hashRow(row("rate", a), List.of(FLOAT_COL)),
                withEps.hashRow(row("rate", b), List.of(FLOAT_COL)),
                "哈希必须精确：容差参与哈希会造成哈希/比较结论不一致（K4）");
        assertTrue(RowDiffer.sameValue(withEps.normalize(a, FLOAT_COL),
                withEps.normalize(b, FLOAT_COL), withEps), "逐列比较应认作相等（eps=1e-6）");
    }

    @Test
    @DisplayName("P8：eps=0 时逐列比较精确")
    void should_exactCompare_When_epsZero() {
        assertFalse(RowDiffer.sameValue(defaults.normalize(Double.valueOf(1.0000001d), FLOAT_COL),
                defaults.normalize(Double.valueOf(1.0d), FLOAT_COL), defaults));
    }

    // ---------------------------------------------------------------- P9

    @Test
    @DisplayName("P9：byte[]{1,2} 与等价 Base64 串同哈希")
    void should_sameHash_When_binaryAndBase64String() {
        String base64 = Base64.getEncoder().encodeToString(new byte[] {1, 2});
        assertEquals(defaults.hashRow(row("blob_col", new byte[] {1, 2}), List.of(BIN_COL)),
                defaults.hashRow(row("blob_col", base64), List.of(BIN_COL)));
    }

    @Test
    @DisplayName("P9：不同二进制内容必须不同哈希")
    void should_diffHash_When_binaryContentDiffers() {
        assertNotEquals(defaults.hashRow(row("blob_col", new byte[] {1, 2}), List.of(BIN_COL)),
                defaults.hashRow(row("blob_col", new byte[] {1, 3}), List.of(BIN_COL)));
    }

    // ---------------------------------------------------------------- P10

    @Test
    @DisplayName("P10：超限 LOB 返回带 TRUNCATED 标记的值")
    void should_markTruncated_When_lobExceedsLimit() {
        Normalizer small = new Normalizer(NormalizeOptions.defaults().withLobCompareLimitBytes(8));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append('x');
        }
        Object normalized = small.normalize(sb.toString(), TEXT);
        assertTrue(Normalizer.isTruncated(normalized), "超限必须显式标记，否则报告无法 WARN");
        assertTrue(normalized.toString().contains("TRUNCATED"));
        assertFalse(Normalizer.isTruncated(small.normalize("short", TEXT)));
    }

    @Test
    @DisplayName("P10：长度参与哈希（前 N 字节相同但总长不同必须报差异）")
    void should_diffHash_When_truncatedLengthDiffers() {
        Normalizer small = new Normalizer(NormalizeOptions.defaults().withLobCompareLimitBytes(4));
        assertNotEquals(small.hashRow(row("name", "abcdefgh"), List.of(TEXT)),
                small.hashRow(row("name", "abcdefghi"), List.of(TEXT)));
    }

    @Test
    @DisplayName("P10：截断后再比对，前缀与长度都相同则相等")
    void should_sameHash_When_truncatedPrefixAndLengthEqual() {
        Normalizer small = new Normalizer(NormalizeOptions.defaults().withLobCompareLimitBytes(4));
        assertEquals(small.hashRow(row("name", "abcdefgh"), List.of(TEXT)),
                small.hashRow(row("name", "abcdefgX"), List.of(TEXT)));
    }

    // ---------------------------------------------------------------- 结构

    @Test
    @DisplayName("哈希输出为 32 位十六进制 md5，且确定性可重复")
    void should_returnStableMd5_When_hashRow() {
        String hash = defaults.hashRow(row("name", "a"), List.of(TEXT));
        assertEquals(32, hash.length());
        assertEquals(hash, defaults.hashRow(row("name", "a"), List.of(TEXT)));
    }

    @Test
    @DisplayName("报告视图必须覆盖全部 11 条规则（CMP-05 不得静默忽略）")
    void should_coverAllRules_When_reportMap() {
        Map<String, String> report = NormalizeOptions.defaults().toReportMap();
        assertEquals(11, report.size());
        for (com.dbsync.common.enums.NormalizeRuleType rule
                : com.dbsync.common.enums.NormalizeRuleType.values()) {
            assertTrue(report.containsKey(rule.name()), "缺规则：" + rule.name());
        }
    }

    /**
     * 单列行辅助。
     *
     * @param name  列名
     * @param value 值
     * @return 行
     */
    private static Map<String, Object> row(String name, Object value) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put(name, value);
        return row;
    }

    /**
     * 多列行辅助（当前用例未使用，保留给后续扩展，避免重复样板）。
     *
     * @param pairs 交替的列名/值
     * @return 行
     */
    private static Map<String, Object> rowOf(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return row;
    }

    /**
     * 断言列顺序无关（额外覆盖：三列乱序）。
     */
    @Test
    @DisplayName("P0：三列乱序仍同哈希")
    void should_sameHash_When_threeColumnsShuffled() {
        ColumnMeta c3 = col("c3", Types.INTEGER, "INTEGER");
        Map<String, Object> row = rowOf("name", "a", "amount", new BigDecimal("2.50"), "c3",
                Integer.valueOf(7));
        List<ColumnMeta> order1 = new ArrayList<ColumnMeta>(List.of(TEXT, NUMERIC, c3));
        List<ColumnMeta> order2 = new ArrayList<ColumnMeta>(List.of(c3, TEXT, NUMERIC));
        assertEquals(defaults.hashRow(row, order1), defaults.hashRow(row, order2));
    }
}
