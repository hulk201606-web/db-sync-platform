package com.dbsync.metadata.dao;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.enums.DiffType;
import com.dbsync.common.model.DiffRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 差异明细 DAO 测试：列差异 JSON 往返、类型统计、修复状态留痕。
 */
class CompareDiffDaoTest extends MetaDaoTestSupport {

    private static final long JOB_ID = 7002L;

    /**
     * 造一条值差异记录。
     *
     * @param id 差异 ID
     * @return 差异
     */
    private DiffRecord valueDiff(long id) {
        Map<String, Map<String, String>> columnDiffs =
                new LinkedHashMap<String, Map<String, String>>();
        Map<String, String> pair = new LinkedHashMap<String, String>();
        pair.put("src", "100.00");
        pair.put("tgt", "100.0");
        columnDiffs.put("AMOUNT", pair);
        return new DiffRecord(id, JOB_ID, 9L, 101L, "[1]", 11L, DiffType.VALUE_DIFF,
                columnDiffs, "{\"ID\":1,\"AMOUNT\":\"100.00\"}",
                "{\"ID\":1,\"AMOUNT\":\"100.0\"}");
    }

    @Test
    @DisplayName("should_roundTripColumnDiffs：列差异 JSON 可原样读回（源/目标值都在）")
    void should_roundTripColumnDiffs() {
        CompareDiffDao dao = new CompareDiffDao(jdbc);
        assertThat(dao.insertBatch(List.of(valueDiff(1L)))).isEqualTo(1);
        DiffRecord loaded = dao.findByJob(JOB_ID, 10).get(0);
        assertThat(loaded.getDiffType()).isEqualTo(DiffType.VALUE_DIFF);
        assertThat(loaded.getColumnDiffs()).containsKey("AMOUNT");
        assertThat(loaded.getColumnDiffs().get("AMOUNT")).containsEntry("src", "100.00");
        assertThat(loaded.getSrcRowJson()).contains("100.00");
        assertThat(loaded.diffColumnNames()).containsExactly("AMOUNT");
    }

    @Test
    @DisplayName("should_countByType：三种类型都出现（缺省 0）")
    void should_countByType() {
        CompareDiffDao dao = new CompareDiffDao(jdbc);
        dao.insertBatch(List.of(valueDiff(1L),
                new DiffRecord(2L, JOB_ID, 9L, 101L, "[2]", 12L, DiffType.ONLY_IN_SOURCE,
                        null, "{}", null),
                new DiffRecord(3L, JOB_ID, 9L, 101L, "[3]", 13L, DiffType.ONLY_IN_TARGET,
                        null, null, "{}")));
        Map<DiffType, Long> counts = dao.countByType(JOB_ID);
        assertThat(counts.get(DiffType.VALUE_DIFF)).isEqualTo(1L);
        assertThat(counts.get(DiffType.ONLY_IN_SOURCE)).isEqualTo(1L);
        assertThat(counts.get(DiffType.ONLY_IN_TARGET)).isEqualTo(1L);
        assertThat(dao.countByJob(JOB_ID)).isEqualTo(3L);
        assertThat(dao.findByJobAndType(JOB_ID, DiffType.ONLY_IN_SOURCE, 10)).hasSize(1);
        assertThat(dao.findByPkHash(JOB_ID, 12L)).hasSize(1);
    }

    @Test
    @DisplayName("should_updateRepairState：修复状态与备份表落库")
    void should_updateRepairState() {
        CompareDiffDao dao = new CompareDiffDao(jdbc);
        dao.insertBatch(List.of(valueDiff(10L), valueDiff(11L)));
        assertThat(dao.updateRepairState(List.of(Long.valueOf(10L), Long.valueOf(11L)),
                "FIXED", "alice", "t_order_bak_20260101")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM sync_compare_diff WHERE job_id=? "
                + "AND repair_state=? AND backup_table=?", Integer.class, JOB_ID, "FIXED",
                "t_order_bak_20260101")).isEqualTo(2);
    }
}
