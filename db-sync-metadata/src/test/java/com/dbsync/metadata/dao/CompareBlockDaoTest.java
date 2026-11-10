package com.dbsync.metadata.dao;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.model.BlockResult;
import com.dbsync.common.model.PkRange;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 比对分块 DAO 测试：一致/不一致块分别可查，下钻标记可写。
 */
class CompareBlockDaoTest extends MetaDaoTestSupport {

    private static final long JOB_ID = 7001L;

    @Test
    @DisplayName("should_insertBatchAndFindMismatched：只返回不一致块供下钻")
    void should_insertBatchAndFindMismatched() {
        CompareBlockDao dao = new CompareBlockDao(jdbc);
        List<BlockResult> blocks = List.of(
                new BlockResult(1L, 0, 101L, new PkRange(0L, 999L, RangeKind.NUMERIC),
                        1000L, 1000L, "sum-1", "sum-1", null, null, true, 0),
                new BlockResult(2L, 1, 101L, new PkRange(1000L, 1999L, RangeKind.NUMERIC),
                        1000L, 998L, "sum-2", "sum-2b", null, null, false, 2));
        assertThat(dao.insertBatch(JOB_ID, blocks)).isEqualTo(2);
        assertThat(dao.findByJob(JOB_ID)).hasSize(2);
        assertThat(dao.findMismatched(JOB_ID)).hasSize(1);
        assertThat(dao.findMismatched(JOB_ID).get(0).getBlockNo()).isEqualTo(1);
        assertThat(dao.countMatched(JOB_ID)).isEqualTo(1L);
        assertThat(dao.countMismatched(JOB_ID)).isEqualTo(1L);
    }

    @Test
    @DisplayName("should_markDrilled：下钻后 drilled=1 且差异行数落库")
    void should_markDrilled() {
        CompareBlockDao dao = new CompareBlockDao(jdbc);
        dao.insertBatch(JOB_ID, List.of(
                new BlockResult(5L, 0, 101L, new PkRange(0L, 99L, RangeKind.NUMERIC),
                        100L, 99L, "a", "b", null, null, false, 1)));
        assertThat(dao.markDrilled(JOB_ID, 0, 3)).isEqualTo(1);
        assertThat(dao.findByJob(JOB_ID).get(0).getDiffRows()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT drilled FROM sync_compare_block WHERE job_id=?",
                Integer.class, JOB_ID)).isEqualTo(1);
    }
}
