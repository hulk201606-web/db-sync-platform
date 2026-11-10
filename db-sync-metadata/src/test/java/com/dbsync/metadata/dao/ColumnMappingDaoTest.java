package com.dbsync.metadata.dao;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.model.ColumnMapping;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 列映射 DAO 测试：批量写入、按表查询（ordinal 有序）、人工覆盖留痕、重新发现时先删后插。
 */
class ColumnMappingDaoTest extends MetaDaoTestSupport {

    private static final long TABLE_MAPPING_ID = 77L;

    @Test
    @DisplayName("should_insertBatchAndKeepOrder：按 ordinal 返回，覆盖信息可读回")
    void should_insertBatchAndKeepOrder() {
        ColumnMappingDao dao = new ColumnMappingDao(jdbc);
        List<ColumnMapping> columns = List.of(
                new ColumnMapping("id", "ID", "BIGINT", "BIGINT", null, null, true, false, 0),
                new ColumnMapping("amount", "AMOUNT", "NUMBER", "DECIMAL(38,10)", "DECIMAL(38,10)",
                        "alice", false, false, 1),
                new ColumnMapping("created", "CREATED", "DATE", "DATETIME(3)", "DATETIME(3)",
                        "alice", false, false, 2));
        assertThat(dao.insertBatch(TABLE_MAPPING_ID, columns)).isEqualTo(3);
        assertThat(dao.countByTableMappingId(TABLE_MAPPING_ID)).isEqualTo(3);
        List<ColumnMapping> loaded = dao.findByTableMappingId(TABLE_MAPPING_ID);
        assertThat(loaded).hasSize(3);
        assertThat(loaded.get(0).getSourceColumn()).isEqualTo("id");
        assertThat(loaded.get(1).getOverrideBy()).isEqualTo("alice");
        assertThat(loaded.get(1).effectiveType()).contains("38,10");
        assertThat(loaded.get(2).getOrdinal()).isEqualTo(2);
    }

    @Test
    @DisplayName("should_replaceOnRediscovery：先删后插不残留旧列")
    void should_replaceOnRediscovery() {
        ColumnMappingDao dao = new ColumnMappingDao(jdbc);
        dao.insertBatch(TABLE_MAPPING_ID, List.of(
                new ColumnMapping("a", "A", "INT", "INT", null, null, false, false, 0),
                new ColumnMapping("b", "B", "INT", "INT", null, null, false, false, 1)));
        assertThat(dao.deleteByTableMappingId(TABLE_MAPPING_ID)).isEqualTo(2);
        dao.insertBatch(TABLE_MAPPING_ID, List.of(
                new ColumnMapping("a", "A", "INT", "INT", null, null, false, false, 0)));
        assertThat(dao.countByTableMappingId(TABLE_MAPPING_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("should_rejectDuplicateColumn：同表同源列重复违反 uk_colmap")
    void should_rejectDuplicateColumn() {
        ColumnMappingDao dao = new ColumnMappingDao(jdbc);
        dao.insertBatch(TABLE_MAPPING_ID, List.of(
                new ColumnMapping("a", "A", "INT", "INT", null, null, false, false, 0)));
        Throwable thrown = null;
        try {
            dao.insertBatch(TABLE_MAPPING_ID, List.of(
                    new ColumnMapping("a", "A2", "INT", "INT", null, null, false, false, 0)));
        } catch (RuntimeException e) {
            thrown = e;
        }
        assertThat(thrown).isNotNull();
        assertThat(dao.countByTableMappingId(TABLE_MAPPING_ID)).isEqualTo(1);
    }
}
