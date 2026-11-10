package com.dbsync.metadata.dao;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.model.CompareVerdictSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 比对作业 DAO 测试：进度推进 + Gate 取数（最新完成作业的通过率与新鲜度）。
 */
class CompareJobDaoTest extends MetaDaoTestSupport {

    private static final long TASK_ID = 8101L;

    @Test
    @DisplayName("should_trackProgress：总块数/完成数/差异块数可推进，完成后可统计")
    void should_trackProgress() {
        CompareJobDao dao = new CompareJobDao(jdbc);
        assertThat(dao.insert(1L, TASK_ID, null, "MANUAL", 100000, 8,
                "{\"decimalTrimZeros\":true}", "alice")).isEqualTo(1);
        assertThat(dao.markRunning(1L, 10)).isEqualTo(1);
        assertThat(dao.updateProgress(1L, 4, 1)).isEqualTo(1);
        CompareJobDao.CompareJobRow row = dao.findById(1L).orElseThrow();
        assertThat(row.getState()).isEqualTo("RUNNING");
        assertThat(row.getTotalBlocks()).isEqualTo(10);
        assertThat(row.getDoneBlocks()).isEqualTo(4);
        assertThat(row.getDiffBlocks()).isEqualTo(1);
        assertThat(dao.countUnfinished(TASK_ID)).isEqualTo(1L);
    }

    @Test
    @DisplayName("should_latestFinishedSnapshot：Gate G1 只认最近一次 FINISHED")
    void should_latestFinishedSnapshot() {
        CompareJobDao dao = new CompareJobDao(jdbc);
        dao.insert(10L, TASK_ID, null, "MANUAL", 100000, 8, null, "alice");
        dao.markRunning(10L, 10);
        dao.finish(10L, 1_000_000L, 50L, 0.99995d, "PASS", "/tmp/r1.html");
        dao.insert(11L, TASK_ID, null, "MANUAL", 100000, 8, null, "alice");
        // 未完成的新作业不能影响取数
        CompareVerdictSnapshot snapshot = dao.latestFinishedSnapshot(TASK_ID).orElseThrow();
        assertThat(snapshot.getJobId()).isEqualTo(10L);
        assertThat(snapshot.getPassRate()).isCloseTo(0.99995d,
                org.assertj.core.data.Offset.offset(1e-9));
        assertThat(snapshot.getVerdict()).isEqualTo("PASS");
        assertThat(snapshot.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("should_emptySnapshot_When_neverFinished：从未完成过返回 empty（Gate 必须拒绝）")
    void should_emptySnapshot_When_neverFinished() {
        CompareJobDao dao = new CompareJobDao(jdbc);
        dao.insert(20L, TASK_ID, null, "MANUAL", 100000, 8, null, "alice");
        assertThat(dao.latestFinishedSnapshot(TASK_ID)).isEmpty();
    }
}
