package com.dbsync.core.read;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.model.PkRange;
import com.dbsync.core.CoreTestSupport;
import com.dbsync.dialect.Dialect;
import com.dbsync.dialect.DialectRegistry;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T19 验收：区间语义 {@code (start, end]} 必须正确（写反会导致重复或丢行），
 * 且大表读取不驻留内存。
 */
class JdbcReaderTest extends CoreTestSupport {

    private final JdbcReader reader = new JdbcReader();

    private Dialect dialect;

    /**
     * 建一张 20 万行的表（H2 用 SYSTEM_RANGE 快速造数）。
     */
    @BeforeEach
    void setUpTable() {
        jdbc.execute("CREATE TABLE t_order (id BIGINT NOT NULL PRIMARY KEY, name VARCHAR(64))");
        java.util.List<Object[]> rows = new java.util.ArrayList<Object[]>(200_000);
        for (long i = 1; i <= 200_000; i++) {
            rows.add(new Object[] {Long.valueOf(i), "n" + i});
        }
        jdbc.batchUpdate("INSERT INTO t_order (id, name) VALUES (?,?)", rows);
        dialect = DialectRegistry.get(com.dbsync.common.enums.DbType.MYSQL);
    }

    /**
     * 借连接读区间。
     *
     * @param range 区间
     * @param limit 期望上限（用于提前停止）
     * @return 读到的 id 列表
     */
    private List<Long> readIds(PkRange range, int limit) {
        List<Long> ids = new ArrayList<Long>();
        try (Connection connection = dataSource.getConnection()) {
            reader.read(connection, dialect, ReadFixture.table(), range, ReadFixture.options(),
                    (row, index) -> {
                        ids.add(((Number) row[0]).longValue());
                        if (ids.size() >= limit) {
                            throw new java.sql.SQLException("stop");
                        }
                    });
        } catch (Exception e) {
            // 提前停止用异常实现（仅测试用）
            if (!"stop".equals(e.getMessage())) {
                throw new IllegalStateException(e);
            }
        }
        return ids;
    }

    @Test
    @DisplayName("should_applyRange_When_(start,end]：read((10,20]) 必须返回 11..20 共 10 行")
    void should_applyRange() {
        List<Long> ids = readIds(new PkRange(BigDecimal.TEN, BigDecimal.valueOf(20),
                RangeKind.NUMERIC), 1000);
        assertThat(ids).containsExactly(11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L);
    }

    @Test
    @DisplayName("should_readAllRows_When_largeTable：20 万行全部读出（流式，不驻留）")
    void should_readAllRows_When_largeTable() {
        long[] counter = new long[1];
        long[] last = new long[1];
        try (Connection connection = dataSource.getConnection()) {
            long rows = reader.read(connection, dialect, ReadFixture.table(),
                    // 区间语义 (start, end]：(1, 200000] 命中 id 2..200000 共 199999 行
                    new PkRange(BigDecimal.ONE, BigDecimal.valueOf(200000), RangeKind.NUMERIC),
                    ReadFixture.options(), (row, index) -> {
                        counter[0]++;
                        last[0] = ((Number) row[0]).longValue();
                    });
            assertThat(rows).isEqualTo(199_999L);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(counter[0]).isEqualTo(199_999L);
        assertThat(last[0]).isEqualTo(200_000L);
    }

    @Test
    @DisplayName("should_readInPkOrder：按主键升序返回（保序前提）")
    void should_readInPkOrder() {
        List<Long> ids = readIds(new PkRange(null, BigDecimal.valueOf(5), RangeKind.NUMERIC), 1000);
        assertThat(ids).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    @DisplayName("should_readNothing_When_rangeEmpty：空区间返回 0 行")
    void should_readNothing_When_rangeEmpty() {
        long rows;
        try (Connection connection = dataSource.getConnection()) {
            rows = reader.read(connection, dialect, ReadFixture.table(),
                    // 区间完全落在数据之外 → 0 行
                    new PkRange(BigDecimal.valueOf(300000), BigDecimal.valueOf(400000),
                            RangeKind.NUMERIC), ReadFixture.options(), (row, index) -> { });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("should_mapColumnsInMetaOrder：列顺序与 TableMeta 一致（id 在第 0 列）")
    void should_mapColumnsInMetaOrder() {
        List<Object[]> rows = new ArrayList<Object[]>();
        try (Connection connection = dataSource.getConnection()) {
            reader.read(connection, dialect, ReadFixture.table(),
                    new PkRange(null, BigDecimal.valueOf(1), RangeKind.NUMERIC),
                    ReadFixture.options(), (row, index) -> rows.add(row));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).hasSize(2);
        assertThat(((Number) rows.get(0)[0]).longValue()).isEqualTo(1L);
        assertThat(String.valueOf(rows.get(0)[1])).isEqualTo("n1");
    }
}
