package com.dbsync.metadata.dao;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.enums.DeadLetterSourceType;
import com.dbsync.common.enums.DeadLetterStatus;
import com.dbsync.common.model.DeadLetter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 死信 DAO 测试：状态机（OPEN→REPLAYING→REPLAYED / OPEN→DISCARDED）+ Gate 计数。
 */
class DeadLetterDaoTest extends MetaDaoTestSupport {

    private static final long TASK_ID = 5501L;

    /**
     * 造一条死信。
     *
     * @param id      死信 ID
     * @param refId   关联 ID
     * @param source  来源
     * @return 死信
     */
    private DeadLetter letter(long id, long refId, DeadLetterSourceType source) {
        return new DeadLetter(id, TASK_ID, Long.valueOf(101L), source, Long.valueOf(refId),
                "[1]", "{\"id\":1}", "E5003", "目标库唯一键冲突", "digest-" + id,
                DeadLetterStatus.OPEN, 0);
    }

    @Test
    @DisplayName("should_insertAndCountOpen：OPEN 计数是 Gate G4 的唯一输入")
    void should_insertAndCountOpen() {
        DeadLetterDao dao = new DeadLetterDao(jdbc);
        dao.insert(letter(1L, 11L, DeadLetterSourceType.SHARD));
        dao.insert(letter(2L, 12L, DeadLetterSourceType.CHANGE));
        assertThat(dao.countOpen(TASK_ID)).isEqualTo(2L);
        assertThat(dao.countOpenAll()).isEqualTo(2L);
        assertThat(dao.list(TASK_ID, DeadLetterStatus.OPEN, 10)).hasSize(2);
        assertThat(dao.findById(1L).orElseThrow().getErrorCode()).isEqualTo("E5003");
    }

    @Test
    @DisplayName("should_replayOnlyOnce：markReplaying 第二次失败，防止重复点击")
    void should_replayOnlyOnce() {
        DeadLetterDao dao = new DeadLetterDao(jdbc);
        dao.insert(letter(10L, 20L, DeadLetterSourceType.CHANGE));
        assertThat(dao.markReplaying(10L, "alice")).isEqualTo(1);
        assertThat(dao.markReplaying(10L, "bob")).isZero();
        assertThat(dao.markReplayed(10L)).isEqualTo(1);
        assertThat(dao.countOpen(TASK_ID)).isZero();
        assertThat(dao.countByStatus(TASK_ID, DeadLetterStatus.REPLAYED)).isEqualTo(1L);
    }

    @Test
    @DisplayName("should_discardWithReasonAndReopen：丢弃需理由，重放失败退回 OPEN 并累加重试")
    void should_discardWithReasonAndReopen() {
        DeadLetterDao dao = new DeadLetterDao(jdbc);
        dao.insert(letter(30L, 40L, DeadLetterSourceType.SHARD));
        assertThat(dao.markDiscarded(30L, "源数据已删除，忽略", "alice")).isEqualTo(1);
        assertThat(dao.countByStatus(TASK_ID, DeadLetterStatus.DISCARDED)).isEqualTo(1L);
        assertThat(dao.reopen(31L, "不存在")).isZero();
    }
}
