package com.dbsync.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.model.ColumnMeta;
import com.dbsync.common.options.NormalizeOptions;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T34 验收：{@link RowHashBuilder} 只允许构造器注入 {@link Normalizer}（K4 门禁）。
 *
 * <p>反射断言"没有无参构造器 + 没有自己的 Normalizer 构造逻辑"靠行为验证更可靠：
 * 若 {@code RowHashBuilder} 内部自己 {@code new Normalizer(defaults)}，
 * 那么注入"开了 TRIM 的 Normalizer"就不会生效 —— 用例 1 必然红。
 */
class RowHashBuilderTest {

    private static final ColumnMeta TEXT =
            ColumnMeta.builder("name", 0, "VARCHAR").jdbcType(Types.VARCHAR).build();

    @Test
    @DisplayName("should_useInjectedNormalizer：注入的规则必须生效（禁止内部自建）")
    void should_useInjectedNormalizer() {
        Normalizer trim = new Normalizer(NormalizeOptions.defaults().withTrimWhitespace(true));
        RowHashBuilder builder = new RowHashBuilder(trim);

        assertEquals(builder.hash(row("name", " a "), List.of(TEXT)),
                builder.hash(row("name", "a"), List.of(TEXT)),
                "若内部自建 Normalizer，这里会因默认不裁剪空格而失败（K4 型故障）");
        assertSame(trim, builder.normalizer());
    }

    @Test
    @DisplayName("should_rejectNullNormalizer_When_construct：禁止无参构造")
    void should_rejectNullNormalizer_When_construct() {
        assertThrows(IllegalArgumentException.class, () -> new RowHashBuilder(null));
        assertEquals(1, RowHashBuilder.class.getDeclaredConstructors().length,
                "只允许一个构造器：注入 Normalizer");
    }

    @Test
    @DisplayName("should_delegateToHashRow：哈希结果与 Normalizer 完全一致")
    void should_delegateToHashRow() {
        Normalizer normalizer = new Normalizer(NormalizeOptions.defaults());
        RowHashBuilder builder = new RowHashBuilder(normalizer);
        Map<String, Object> row = row("name", "abc");
        assertEquals(normalizer.hashRow(row, List.of(TEXT)), builder.hash(row, List.of(TEXT)));
    }

    @Test
    @DisplayName("should_buildRowMap_When_columnValuesGiven：数组→行映射按列顺序")
    void should_buildRowMap_When_columnValuesGiven() {
        RowHashBuilder builder = new RowHashBuilder(new Normalizer(NormalizeOptions.defaults()));
        ColumnMeta id = ColumnMeta.builder("id", 0, "BIGINT").jdbcType(Types.BIGINT).build();
        Map<String, Object> row = builder.rowOf(new Object[] {Long.valueOf(1L), "a"},
                List.of(id, TEXT));
        assertEquals(Long.valueOf(1L), row.get("id"));
        assertEquals("a", row.get("name"));
    }

    @Test
    @DisplayName("should_fillNull_When_valueArrayShorter：列数不一致时不抛异常")
    void should_fillNull_When_valueArrayShorter() {
        RowHashBuilder builder = new RowHashBuilder(new Normalizer(NormalizeOptions.defaults()));
        Map<String, Object> row = builder.rowOf(new Object[] {Long.valueOf(1L)},
                List.of(TEXT, TEXT));
        assertTrue(row.containsKey("name"));
        assertEquals(null, row.get("name"));
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
}
