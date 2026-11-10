package com.dbsync.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.enums.DiffType;
import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.model.DiffRecord;
import com.dbsync.common.model.PkRange;
import com.dbsync.common.model.TableMeta;
import com.dbsync.common.options.NormalizeOptions;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T36 验收：差异下钻的 4 条硬要求。
 *
 * <ol>
 *   <li>{@code VALUE_DIFF} 必须给出列级 {@code {col, src, tgt}}；</li>
 *   <li>{@code ONLY_IN_SOURCE} / {@code ONLY_IN_TARGET} 必须正确区分；</li>
 *   <li>3 列差异必须全部列出（<b>不因第一处差异短路</b>，PRD CMP-03）；</li>
 *   <li>敏感列（手机号）在 {@code DiffRecord} 中必须已脱敏。</li>
 * </ol>
 */
class RowDifferTest extends CompareTestSupport {

    private final Normalizer normalizer = new Normalizer(NormalizeOptions.defaults());
    private final RowDiffer differ = new RowDiffer(normalizer);

    /**
     * 下钻整表（无界区间）。
     *
     * @param table 表元数据
     * @return 差异
     */
    private List<DiffRecord> diffAll(TableMeta table) {
        try (Connection src = sourceDs.getConnection();
             Connection tgt = targetDs.getConnection()) {
            return differ.diff(src, dialect, tgt, dialect, table,
                    PkRange.unbounded(RangeKind.NUMERIC), 1L, 2L, 3L, normalizer);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("should_reportValueDiffWithColumns_When_valueChanged：列级 {col,src,tgt}")
    void should_reportValueDiffWithColumns_When_valueChanged() {
        createBothTables("t_rd1");
        insertBoth("t_rd1", 1, 3);
        updateName(targetDs, "t_rd1", 2, "changed");

        List<DiffRecord> diffs = diffAll(tableMeta("t_rd1", 3));

        assertEquals(1, diffs.size());
        DiffRecord diff = diffs.get(0);
        assertEquals(DiffType.VALUE_DIFF, diff.getDiffType());
        Map<String, String> nameDiff = diff.getColumnDiffs().get("name");
        assertNotNull(nameDiff, "必须给出列级明细");
        assertEquals("n2", nameDiff.get("src"));
        assertEquals("changed", nameDiff.get("tgt"));
        assertNotNull(diff.getSrcRowJson(), "修复取证需要完整源行");
        assertNotNull(diff.getTgtRowJson());
        assertEquals(1L, diff.getJobId());
        assertEquals(2L, diff.getBlockId());
        assertEquals(3L, diff.getTableMappingId());
    }

    @Test
    @DisplayName("should_reportOnlyInSourceAndTarget_When_rowMissing：仅源/仅目标正确区分")
    void should_reportOnlyInSourceAndTarget_When_rowMissing() {
        createBothTables("t_rd2");
        insertBoth("t_rd2", 1, 3);
        exec(sourceDs, "DELETE FROM t_rd2 WHERE id=1");
        exec(targetDs, "DELETE FROM t_rd2 WHERE id=3");

        List<DiffRecord> diffs = diffAll(tableMeta("t_rd2", 3));

        assertEquals(2, diffs.size());
        DiffRecord onlyTarget = findByPk(diffs, 1L);
        DiffRecord onlySource = findByPk(diffs, 3L);
        assertEquals(DiffType.ONLY_IN_TARGET, onlyTarget.getDiffType());
        assertEquals(DiffType.ONLY_IN_SOURCE, onlySource.getDiffType());
        assertNull(onlyTarget.getSrcRowJson(), "仅目标有的行不应有源行快照");
        assertNull(onlySource.getTgtRowJson());
        assertTrue(onlySource.getDiffType().isRepairableByReplay(),
                "仅源有的行可以向目标回放");
        assertFalse(onlyTarget.getDiffType().isRepairableByReplay());
    }

    @Test
    @DisplayName("should_listAllThreeColumns_When_threeColumnsDiff：不短路")
    void should_listAllThreeColumns_When_threeColumnsDiff() {
        String ddl = "CREATE TABLE t_rd3 (id BIGINT NOT NULL PRIMARY KEY, c1 VARCHAR(32), "
                + "c2 VARCHAR(32), c3 VARCHAR(32))";
        exec(sourceDs, "DROP TABLE IF EXISTS t_rd3", ddl);
        exec(targetDs, "DROP TABLE IF EXISTS t_rd3", ddl);
        exec(sourceDs, "INSERT INTO t_rd3 VALUES (1,'a','b','c')");
        exec(targetDs, "INSERT INTO t_rd3 VALUES (1,'x','y','z')");

        List<ColumnMeta> extra = List.of(
                ColumnMeta.builder("c1", 1, "VARCHAR").jdbcType(Types.VARCHAR).build(),
                ColumnMeta.builder("c2", 2, "VARCHAR").jdbcType(Types.VARCHAR).build(),
                ColumnMeta.builder("c3", 3, "VARCHAR").jdbcType(Types.VARCHAR).build());

        List<DiffRecord> diffs = diffAll(tableMeta("t_rd3", 1, extra));

        assertEquals(1, diffs.size());
        assertEquals(List.of("c1", "c2", "c3"), diffs.get(0).diffColumnNames(),
                "3 列差异必须全部列出（PRD CMP-03 明确禁止短路）");
    }

    @Test
    @DisplayName("should_maskSensitive_When_phoneColumn：手机号已脱敏")
    void should_maskSensitive_When_phoneColumn() {
        String ddl = "CREATE TABLE t_rd4 (id BIGINT NOT NULL PRIMARY KEY, phone VARCHAR(32))";
        exec(sourceDs, "DROP TABLE IF EXISTS t_rd4", ddl);
        exec(targetDs, "DROP TABLE IF EXISTS t_rd4", ddl);
        exec(sourceDs, "INSERT INTO t_rd4 VALUES (1,'13800138000')");
        exec(targetDs, "INSERT INTO t_rd4 VALUES (1,'13900139000')");

        List<ColumnMeta> extra = List.of(ColumnMeta.builder("phone", 1, "VARCHAR")
                .jdbcType(Types.VARCHAR).build());
        List<DiffRecord> diffs = diffAll(tableMeta("t_rd4", 1, extra));

        assertEquals(1, diffs.size());
        Map<String, String> phoneDiff = diffs.get(0).getColumnDiffs().get("phone");
        assertTrue(phoneDiff.get("src").contains("****"), "手机号必须脱敏：" + phoneDiff);
        assertTrue(phoneDiff.get("tgt").contains("****"));
        assertFalse(phoneDiff.get("src").contains("13800138000"), "不得出现完整手机号");
        assertTrue(phoneDiff.get("src").startsWith("138"));
        assertTrue(phoneDiff.get("src").endsWith("8000"));
    }

    @Test
    @DisplayName("should_returnEmpty_When_rowsIdentical：完全一致时无差异")
    void should_returnEmpty_When_rowsIdentical() {
        createBothTables("t_rd5");
        insertBoth("t_rd5", 1, 50);
        assertTrue(diffAll(tableMeta("t_rd5", 50)).isEmpty());
    }

    @Test
    @DisplayName("should_ignoreDecimalScale_When_compare：1.10 与 1.1 不算差异（P3 生效）")
    void should_ignoreDecimalScale_When_compare() {
        createBothTables("t_rd6");
        insertBoth("t_rd6", 1, 1);
        exec(sourceDs, "UPDATE t_rd6 SET amount=1.10 WHERE id=1");
        exec(targetDs, "UPDATE t_rd6 SET amount=1.1 WHERE id=1");
        assertTrue(diffAll(tableMeta("t_rd6", 1)).isEmpty(),
                "归一化共用同一实例，标度差异不应报为差异（否则误报率 100%）");
    }

    @Test
    @DisplayName("should_useTolerance_When_floatColumnAndEps：浮点容差在逐列比较生效")
    void should_useTolerance_When_floatColumnAndEps() {
        String ddl = "CREATE TABLE t_rd7 (id BIGINT NOT NULL PRIMARY KEY, rate DOUBLE)";
        exec(sourceDs, "DROP TABLE IF EXISTS t_rd7", ddl);
        exec(targetDs, "DROP TABLE IF EXISTS t_rd7", ddl);
        exec(sourceDs, "INSERT INTO t_rd7 VALUES (1, 0.3000000001)");
        exec(targetDs, "INSERT INTO t_rd7 VALUES (1, 0.3)");

        Normalizer strictNormalizer = new Normalizer(NormalizeOptions.defaults());
        Normalizer epsNormalizer =
                new Normalizer(NormalizeOptions.defaults().withFloatTolerance(1e-9));
        List<ColumnMeta> extra = List.of(ColumnMeta.builder("rate", 1, "DOUBLE")
                .jdbcType(Types.DOUBLE).build());
        TableMeta table = tableMeta("t_rd7", 1, extra);

        try (Connection src = sourceDs.getConnection();
             Connection tgt = targetDs.getConnection()) {
            List<DiffRecord> strict = differ.diff(src, dialect, tgt, dialect, table,
                    PkRange.unbounded(RangeKind.NUMERIC), 1L, 1L, 1L, strictNormalizer);
            List<DiffRecord> lenient = differ.diff(src, dialect, tgt, dialect, table,
                    PkRange.unbounded(RangeKind.NUMERIC), 1L, 1L, 1L, epsNormalizer);
            assertEquals(1, strict.size(), "eps=0 时应报差异（宁可报差异，不可漏报）");
            assertEquals(0, lenient.size(), "eps=1e-9 时应视为相等");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 按主键值找差异记录。
     *
     * @param diffs 差异列表
     * @param pk    主键值
     * @return 差异记录
     */
    private static DiffRecord findByPk(List<DiffRecord> diffs, long pk) {
        for (DiffRecord diff : diffs) {
            if (diff.getPkJson().contains("\"" + pk + "\"")
                    || diff.getPkJson().contains(":" + pk)) {
                return diff;
            }
        }
        throw new AssertionError("找不到主键为 " + pk + " 的差异：" + diffs);
    }
}
