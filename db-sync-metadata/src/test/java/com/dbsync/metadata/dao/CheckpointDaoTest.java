package com.dbsync.metadata.dao;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.enums.ScopeType;
import com.dbsync.common.model.Checkpoint;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 断点 DAO 测试：upsert 幂等 + 版本 CAS（I1 的实现依赖"CAS 失败即视为位点未推进"）。
 */
class CheckpointDaoTest extends MetaDaoTestSupport {

    private static final long TASK_ID = 3301L;
    private static final long TABLE_MAPPING_ID = 44L;

    @Test
    @DisplayName("should_insertThenLoad：首次保存走 INSERT，可原样读回")
    void should_insertThenLoad() {
        CheckpointDao dao = new CheckpointDao(jdbc);
        Checkpoint initial = Checkpoint.initial(TASK_ID, TABLE_MAPPING_ID, ScopeType.SHARD, 7);
        assertThat(dao.save(initial)).isEqualTo(1);
        Checkpoint loaded = dao.load(TASK_ID, TABLE_MAPPING_ID, ScopeType.SHARD, 7).orElseThrow();
        assertThat(loaded.getCommittedRows()).isZero();
        assertThat(loaded.getScopeNo()).isEqualTo(7);
        assertThat(loaded.getVersion()).isZero();
    }

    @Test
    @DisplayName("should_upsertWithVersionCas：第二次保存 UPDATE 命中，版本 +1，行数不重复")
    void should_upsertWithVersionCas() {
        CheckpointDao dao = new CheckpointDao(jdbc);
        Checkpoint initial = Checkpoint.initial(TASK_ID, TABLE_MAPPING_ID, ScopeType.SHARD, 1);
        dao.save(initial);
        Checkpoint advanced = initial.withLastPk("1000").withCommitted(500L);
        assertThat(dao.save(advanced)).isEqualTo(1);
        Checkpoint loaded = dao.load(TASK_ID, TABLE_MAPPING_ID, ScopeType.SHARD, 1).orElseThrow();
        assertThat(loaded.getLastPk()).isEqualTo("1000");
        assertThat(loaded.getCommittedRows()).isEqualTo(500L);
        assertThat(loaded.getCommittedBatches()).isEqualTo(1L);
        assertThat(loaded.getVersion()).isEqualTo(1);
        // 仍只有一行
        assertThat(jdbc.queryForObject("SELECT COUNT(1) FROM sync_checkpoint WHERE task_id=?",
                Integer.class, TASK_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("should_rejectStaleVersion：用旧版本保存返回 0（不允许位点被并发覆盖）")
    void should_rejectStaleVersion() {
        CheckpointDao dao = new CheckpointDao(jdbc);
        Checkpoint initial = Checkpoint.initial(TASK_ID, TABLE_MAPPING_ID, ScopeType.SHARD, 2);
        dao.save(initial);
        dao.save(initial.withCommitted(100L));
        // 用 version=0 再保存 → CAS 失败
        assertThat(dao.save(initial.withCommitted(200L))).isZero();
        assertThat(dao.load(TASK_ID, TABLE_MAPPING_ID, ScopeType.SHARD, 2).orElseThrow()
                .getCommittedRows()).isEqualTo(100L);
    }

    @Test
    @DisplayName("should_keepScopesIsolated：同 task 不同 scope/scopeNo 互不影响")
    void should_keepScopesIsolated() {
        CheckpointDao dao = new CheckpointDao(jdbc);
        dao.save(Checkpoint.initial(TASK_ID, 0L, ScopeType.GROUP, 1)
                .withPosition(5L, Instant.now(), null));
        dao.save(Checkpoint.initial(TASK_ID, 0L, ScopeType.GROUP, 2)
                .withPosition(9L, Instant.now(), null));
        assertThat(dao.findByScope(TASK_ID, ScopeType.GROUP)).hasSize(2);
        assertThat(dao.load(TASK_ID, 0L, ScopeType.GROUP, 1).orElseThrow().getLastSeq())
                .isEqualTo(5L);
        assertThat(dao.load(TASK_ID, 0L, ScopeType.GROUP, 2).orElseThrow().getLastSeq())
                .isEqualTo(9L);
    }
}
