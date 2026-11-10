package com.dbsync.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.model.ColumnMeta;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T28 验收：分组器的"归一化一致"与"均匀分布"。
 */
class GroupAssignerTest extends CdcTestSupport {

    /**
     * 主键列元数据（只用于长度校验）。
     *
     * @return 单列主键
     */
    private List<ColumnMeta> pkCols() {
        return List.of(ColumnMeta.builder("id", 0, "BIGINT").jdbcType(java.sql.Types.BIGINT).build());
    }

    @Test
    @DisplayName("should_sameGroup_When_pkTypesDiffer：Long 1 与 BigDecimal 1 必须同组")
    void should_sameGroup_When_pkTypesDiffer() {
        int groupLong = groupAssigner.groupOf(List.of(Long.valueOf(1L)), pkCols(), 16);
        int groupDecimal = groupAssigner.groupOf(List.of(new BigDecimal("1")), pkCols(), 16);
        int groupDecimalScale = groupAssigner.groupOf(List.of(new BigDecimal("1.00")), pkCols(), 16);

        assertEquals(groupLong, groupDecimal, "同一主键的不同 Java 类型必须落同一组（保序前提）");
        assertEquals(groupLong, groupDecimalScale, "1 与 1.00 是同一个主键");
    }

    @Test
    @DisplayName("should_distributeEvenly_When_100kKeys：10 万主键落 16 组，每组 6.25%±1%")
    void should_distributeEvenly_When_100kKeys() {
        int groupCount = 16;
        int total = 100_000;
        Map<Integer, Integer> counts = new HashMap<>();
        Random random = new Random(20241109L);
        for (int i = 0; i < total; i++) {
            long pk = Math.abs(random.nextLong());
            int group = groupAssigner.groupOf(List.of(Long.valueOf(pk)), pkCols(), groupCount);
            counts.merge(Integer.valueOf(group), Integer.valueOf(1), Integer::sum);
        }

        assertEquals(groupCount, counts.size(), "每个组都应被覆盖到：" + counts);
        double expected = 100d / groupCount;
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            double actual = entry.getValue().doubleValue() * 100d / total;
            assertTrue(Math.abs(actual - expected) <= 1.0d,
                    "组 " + entry.getKey() + " 占比 " + actual + "% 偏离期望 " + expected + "%");
        }
    }

    @Test
    @DisplayName("should_groupZero_When_singleGroup：组数为 1 时恒为 0")
    void should_groupZero_When_singleGroup() {
        assertEquals(0, groupAssigner.groupOf(List.of(Long.valueOf(7L)), pkCols(), 1));
        assertEquals(0, groupAssigner.groupOf(List.of(Long.valueOf(7L)), pkCols(), 0));
    }

    @Test
    @DisplayName("should_neverNegative_When_hashIsNegative：负哈希也必须落到合法组号")
    void should_neverNegative_When_hashIsNegative() {
        for (long pk = -500L; pk <= 500L; pk++) {
            int group = groupAssigner.groupOf(List.of(Long.valueOf(pk)), pkCols(), 7);
            assertTrue(group >= 0 && group < 7, "组号越界：" + group);
        }
    }

    @Test
    @DisplayName("should_reject_When_tupleLengthMismatch：主键元组长度不匹配直接报错")
    void should_reject_When_tupleLengthMismatch() {
        List<Object> tuple = new ArrayList<>();
        tuple.add(Long.valueOf(1L));
        tuple.add(Long.valueOf(2L));
        assertThrows(DbSyncException.class,
                () -> groupAssigner.groupOf(tuple, pkCols(), 16));
    }

    @Test
    @DisplayName("should_sameGroup_When_compositeKeyOrderStable：复合主键顺序稳定")
    void should_sameGroup_When_compositeKeyOrderStable() {
        List<Object> tuple = List.of(Long.valueOf(3L), "abc");
        int first = groupAssigner.groupOf(tuple, null, 8);
        int second = groupAssigner.groupOf(tuple, null, 8);
        assertEquals(first, second);
    }
}
