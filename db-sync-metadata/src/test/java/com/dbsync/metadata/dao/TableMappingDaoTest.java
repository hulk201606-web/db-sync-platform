package com.dbsync.metadata.dao;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.model.TableMapping;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 映射 DAO 测试（表映射 + 列映射，含唯一约束与批量写入）。
 */
class TableMappingDaoTest extends MetaDaoTestSupport {

    private static final long TASK_ID = 2001L;

    /**
     * 造一条表映射。
     *
     * @param id          映射 ID
     * @param sourceTable 源表
     * @return 表映射
     */
    private TableMapping mapping(long id, String sourceTable) {
        return new TableMapping(id, TASK_ID, "orders", sourceTable, "orders", sourceTable,
                true, "NUMERIC", 1000L, "CREATE TABLE ...", false, true);
    }

    @Test
    @DisplayName("should_insertBatchAndFind：批量写入后可查，enabled 过滤生效")
    void should_insertBatchAndFind() {
        TableMappingDao dao = new TableMappingDao(jdbc);
        assertThat(dao.insertBatch(List.of(mapping(1L, "t_order"), mapping(2L, "t_item"))))
                .isEqualTo(2);
        assertThat(dao.findByTask(TASK_ID)).hasSize(2);
        assertThat(dao.findEnabledByTask(TASK_ID)).hasSize(2);
        dao.setEnabled(2L, false);
        assertThat(dao.findEnabledByTask(TASK_ID)).hasSize(1);
        assertThat(dao.findById(1L).orElseThrow().getSourceTable()).isEqualTo("t_order");
    }

    @Test
    @DisplayName("should_rejectDuplicateMapping：同任务同源表重复映射违反 uk_map")
    void should_rejectDuplicateMapping() {
        TableMappingDao dao = new TableMappingDao(jdbc);
        dao.insert(mapping(10L, "t_order"));
        Throwable thrown = null;
        try {
            dao.insert(mapping(11L, "t_order"));
        } catch (RuntimeException e) {
            thrown = e;
        }
        assertThat(thrown).isNotNull();
        assertThat(dao.findByTask(TASK_ID)).hasSize(1);
    }

    @Test
    @DisplayName("should_updateDdlAndEstimate：DDL 与行数估算可回写")
    void should_updateDdlAndEstimate() {
        TableMappingDao dao = new TableMappingDao(jdbc);
        dao.insert(mapping(20L, "t_order"));
        assertThat(dao.updateDdl(20L, "CREATE TABLE `t_order` (...)", "[\"R9\"]")).isEqualTo(1);
        assertThat(dao.updateRowCountEstimate(20L, 123456L)).isEqualTo(1);
        assertThat(dao.markDdlApplied(20L)).isEqualTo(1);
        TableMapping after = dao.findById(20L).orElseThrow();
        assertThat(after.getGeneratedDdl()).contains("CREATE TABLE");
        assertThat(after.getRowCountEstimate()).isEqualTo(123456L);
        assertThat(after.isDdlApplied()).isTrue();
    }
}
