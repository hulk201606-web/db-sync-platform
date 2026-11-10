package com.dbsync.metadata.dao;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.model.SyncTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 任务 DAO 测试：状态跃迁必须带"期望状态"条件（跨实例并发安全）。
 */
class SyncTaskDaoTest extends MetaDaoTestSupport {

    /**
     * 造一个任务。
     *
     * @param id 任务 ID
     * @return 任务
     */
    private SyncTask task(long id) {
        return new SyncTask(id, "task-" + id, 1L, 2L, 16, 4, 1000, 2_000_000, 0L, 3,
                IncrementalMode.TRIGGER_LOG, 8, TaskState.DRAFT);
    }

    @Test
    @DisplayName("should_insertAndFind：新增后可按 ID 与状态查到")
    void should_insertAndFind() {
        SyncTaskDao dao = new SyncTaskDao(jdbc);
        assertThat(dao.insert(task(101L), "alice")).isEqualTo(1);
        assertThat(dao.findById(101L).orElseThrow().getName()).isEqualTo("task-101");
        assertThat(dao.findByState(TaskState.DRAFT)).hasSize(1);
        assertThat(dao.findAll()).hasSize(1);
        assertThat(dao.stateOf(101L)).contains(TaskState.DRAFT);
    }

    @Test
    @DisplayName("should_transitionOnlyWhenStateMatches：期望状态不符则 0 行")
    void should_transitionOnlyWhenStateMatches() {
        SyncTaskDao dao = new SyncTaskDao(jdbc);
        dao.insert(task(102L), "alice");
        assertThat(dao.transition(102L, TaskState.DRAFT, TaskState.READY)).isEqualTo(1);
        // 重复同一跃迁 → 0 行（已不在 DRAFT）
        assertThat(dao.transition(102L, TaskState.DRAFT, TaskState.READY)).isZero();
        assertThat(dao.stateOf(102L)).contains(TaskState.READY);
        // 非法路径由 TaskState 自身校验，这里只验证 DAO 不越权
        assertThat(dao.transition(102L, TaskState.READY, TaskState.RUNNING)).isEqualTo(1);
        assertThat(dao.findById(102L).orElseThrow().getStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("should_markFailedAndSoftDelete：失败落错误、软删后不可见")
    void should_markFailedAndSoftDelete() {
        SyncTaskDao dao = new SyncTaskDao(jdbc);
        dao.insert(task(103L), "alice");
        assertThat(dao.markFailed(103L, "源库不可用")).isEqualTo(1);
        SyncTask failed = dao.findById(103L).orElseThrow();
        assertThat(failed.getState()).isEqualTo(TaskState.FAILED);
        assertThat(failed.getLastError()).isEqualTo("源库不可用");
        assertThat(dao.softDelete(103L)).isEqualTo(1);
        assertThat(dao.findById(103L)).isEmpty();
        assertThat(dao.findAll()).isEmpty();
    }
}
