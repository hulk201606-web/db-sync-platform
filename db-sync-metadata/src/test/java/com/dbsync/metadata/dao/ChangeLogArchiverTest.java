package com.dbsync.metadata.dao;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.enums.ChangeOp;
import com.dbsync.common.enums.ChangeStatus;
import com.dbsync.common.enums.DeadLetterSourceType;
import com.dbsync.common.enums.DeadLetterStatus;
import com.dbsync.common.model.ChangeEvent;
import com.dbsync.common.model.DeadLetter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T16 验收：归档只删已应用（NEW/DEAD 永不删），死信只删人工已决策的行。
 */
class ChangeLogArchiverTest extends MetaDaoTestSupport {

    private static final long TASK_ID = 6601L;

    /**
     * 插入混合状态的变更日志：3 NEW、3 APPLIED、2 DEAD。
     *
     * @param dao 变更日志 DAO
     */
    private void seedMixed(ChangeLogDao dao) {
        List<ChangeEvent> events = new ArrayList<ChangeEvent>();
        for (long seq = 1; seq <= 8; seq++) {
            events.add(new ChangeEvent(seq * 10, TASK_ID, 101L, 1, seq, seq, Instant.now(),
                    ChangeOp.INSERT, List.<Object>of(Long.valueOf(seq)), null,
                    Map.<String, Object>of("id", Long.valueOf(seq)), null,
                    ChangeStatus.NEW, 0, null, null));
        }
        dao.insertBatch(events);
        dao.markApplied(List.of(Long.valueOf(10L), Long.valueOf(20L), Long.valueOf(30L)),
                ChangeStatus.APPLIED);
        dao.markDead(40L, "e");
        dao.markDead(50L, "e");
    }

    @Test
    @DisplayName("archiveApplied 只删 APPLIED：NEW 与 DEAD 必须保留")
    void should_archiveOnlyApplied() {
        ChangeLogDao dao = new ChangeLogDao(jdbc);
        seedMixed(dao);
        ChangeLogArchiver archiver = new ChangeLogArchiver(jdbc, dao);
        assertThat(archiver.countArchivable(Instant.now().plusSeconds(60))).isEqualTo(3L);
        int deleted = archiver.archiveApplied(Instant.now().plusSeconds(60), 2);
        assertThat(deleted).isEqualTo(3);
        assertThat(dao.countByStatus(TASK_ID, ChangeStatus.NEW)).isEqualTo(3L);
        assertThat(dao.countByStatus(TASK_ID, ChangeStatus.DEAD)).isEqualTo(2L);
        assertThat(dao.countByStatus(TASK_ID, ChangeStatus.APPLIED)).isZero();
    }

    @Test
    @DisplayName("archiveApplied 分批：batchLimit 生效，单批不超限")
    void should_archiveInBatches() {
        ChangeLogDao dao = new ChangeLogDao(jdbc);
        seedMixed(dao);
        ChangeLogArchiver archiver = new ChangeLogArchiver(jdbc, dao);
        int deleted = archiver.archiveAppliedOfTask(TASK_ID, Instant.now().plusSeconds(60), 1);
        assertThat(deleted).isEqualTo(3);
    }

    @Test
    @DisplayName("purgeDeadLetters 只删 DISCARDED/REPLAYED，OPEN 永不删")
    void should_purgeOnlyDecidedDeadLetters() {
        ChangeLogDao dao = new ChangeLogDao(jdbc);
        DeadLetterDao deadLetterDao = new DeadLetterDao(jdbc);
        seedMixed(dao);
        DeadLetter open = new DeadLetter(1L, TASK_ID, Long.valueOf(101L),
                DeadLetterSourceType.CHANGE, Long.valueOf(40L), "[1]", "{}", "E5001", "boom",
                "digest", DeadLetterStatus.OPEN, 0);
        DeadLetter discarded = new DeadLetter(2L, TASK_ID, Long.valueOf(101L),
                DeadLetterSourceType.CHANGE, Long.valueOf(50L), "[2]", "{}", "E5001", "boom",
                "digest", DeadLetterStatus.OPEN, 0);
        DeadLetter replayed = new DeadLetter(3L, TASK_ID, Long.valueOf(101L),
                DeadLetterSourceType.SHARD, Long.valueOf(60L), "[3]", "{}", "E5001", "boom",
                "digest", DeadLetterStatus.OPEN, 0);
        deadLetterDao.insert(open);
        deadLetterDao.insert(discarded);
        deadLetterDao.insert(replayed);
        deadLetterDao.markDiscarded(2L, "数据已手工修正", "alice");
        deadLetterDao.markReplayed(3L);

        ChangeLogArchiver archiver = new ChangeLogArchiver(jdbc, dao);
        int purged = archiver.purgeDeadLetters(Instant.now().plusSeconds(60));
        assertThat(purged).isEqualTo(2);
        assertThat(deadLetterDao.countByStatus(TASK_ID, DeadLetterStatus.OPEN)).isEqualTo(1L);
        assertThat(deadLetterDao.countByStatus(TASK_ID, DeadLetterStatus.DISCARDED)).isZero();
    }

    @Test
    @DisplayName("归档不动 NEW：未消费的变更即使很老也不删（否则数据永久丢失）")
    void should_neverArchiveNew() {
        ChangeLogDao dao = new ChangeLogDao(jdbc);
        ChangeEvent event = new ChangeEvent(1L, TASK_ID, 101L, 1, 1L, 1L,
                Instant.now().minusSeconds(86400), ChangeOp.INSERT, List.<Object>of(Long.valueOf(1L)),
                null, Map.<String, Object>of("id", Long.valueOf(1L)), null, ChangeStatus.NEW,
                0, null, null);
        dao.insert(event, null, null);
        ChangeLogArchiver archiver = new ChangeLogArchiver(jdbc, dao);
        archiver.archiveApplied(Instant.now().plusSeconds(60), 100);
        assertThat(dao.backlog(TASK_ID)).isEqualTo(1L);
    }
}
