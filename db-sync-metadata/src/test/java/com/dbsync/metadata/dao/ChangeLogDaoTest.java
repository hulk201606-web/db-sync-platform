package com.dbsync.metadata.dao;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.enums.ChangeOp;
import com.dbsync.common.enums.ChangeStatus;
import com.dbsync.common.model.ChangeEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T15 验收（保序核心）：{@code safeSeq} 必须是 {@code MIN(seq)-1}，
 * 乱序应用时<b>不允许</b>把水位推到已应用的大序号上。
 */
class ChangeLogDaoTest extends MetaDaoTestSupport {

    private static final long TASK_ID = 7001L;
    private static final int GROUP_NO = 3;

    /**
     * 造一条变更事件。
     *
     * @param seq 序号
     * @return 事件
     */
    private ChangeEvent event(long seq) {
        return new ChangeEvent(seq * 10 + 1, TASK_ID, 101L, GROUP_NO, seq * 31, seq,
                Instant.now().minusMillis(1000 - seq), ChangeOp.UPDATE,
                List.<Object>of(Long.valueOf(seq)), null,
                Map.<String, Object>of("id", Long.valueOf(seq), "name", "n" + seq),
                List.of("name"), ChangeStatus.NEW, 0, null, null);
    }

    /**
     * 重建 DAO（父类在每个测试前重建 DataSource）。
     *
     * @return DAO
     */
    private ChangeLogDao freshDao() {
        return new ChangeLogDao(jdbc);
    }

    @Test
    @DisplayName("should_pollBatchInSeqOrder：按 seq 升序返回 NEW")
    void should_pollBatchInSeqOrder() {
        ChangeLogDao d = freshDao();
        for (long seq = 1; seq <= 3; seq++) {
            d.insert(event(seq), "trace-" + seq, "inst-A");
        }
        List<ChangeEvent> batch = d.pollBatch(TASK_ID, GROUP_NO, 10);
        assertThat(batch).hasSize(3);
        assertThat(batch.get(0).getSeq()).isEqualTo(1L);
        assertThat(batch.get(2).getSeq()).isEqualTo(3L);
        assertThat(batch.get(0).getAfter()).containsEntry("name", "n1");
        // 主键元组经 JSON 往返，数值会被还原为 BigDecimal（精度无损），故按字符串断言
        assertThat(batch.get(0).getPkTuple()).hasSize(1);
        assertThat(String.valueOf(batch.get(0).getPkTuple().get(0))).isEqualTo("1");
    }

    @Test
    @DisplayName("should_returnMinusOne_When_outOfOrderApplied ★ 核心断言：seq 1,2,3 中 2 未应用 → safeSeq=1")
    void should_returnMinusOne_When_outOfOrderApplied() {
        ChangeLogDao d = freshDao();
        for (long seq = 1; seq <= 3; seq++) {
            d.insert(event(seq), null, null);
        }
        // 乱序应用：1 与 3 已应用，2 仍 NEW
        d.markApplied(Arrays.asList(Long.valueOf(11L), Long.valueOf(31L)), ChangeStatus.APPLIED);
        assertThat(d.safeSeq(TASK_ID, GROUP_NO)).isEqualTo(1L);
        assertThat(d.groupBacklog(TASK_ID, GROUP_NO)).isEqualTo(1L);
    }

    @Test
    @DisplayName("should_returnMaxSeq_When_noNew：组内无未应用时水位等于最大已应用序号")
    void should_returnMaxSeq_When_noNew() {
        ChangeLogDao d = freshDao();
        for (long seq = 1; seq <= 3; seq++) {
            d.insert(event(seq), null, null);
        }
        d.markApplied(Arrays.asList(Long.valueOf(11L), Long.valueOf(21L), Long.valueOf(31L)),
                ChangeStatus.APPLIED);
        assertThat(d.safeSeq(TASK_ID, GROUP_NO)).isEqualTo(3L);
        assertThat(d.groupBacklog(TASK_ID, GROUP_NO)).isZero();
    }

    @Test
    @DisplayName("should_markOnlyTargetStatus：markApplied 不改其他状态，DEAD 不计入积压")
    void should_markOnlyTargetStatus() {
        ChangeLogDao d = freshDao();
        for (long seq = 1; seq <= 4; seq++) {
            d.insert(event(seq), null, null);
        }
        d.markApplied(Arrays.asList(Long.valueOf(11L)), ChangeStatus.APPLIED);
        d.markDead(21L, "不可重试：数值溢出");
        assertThat(d.countByStatus(TASK_ID, ChangeStatus.NEW)).isEqualTo(2L);
        assertThat(d.countByStatus(TASK_ID, ChangeStatus.DEAD)).isEqualTo(1L);
        // I4：只有 NEW 阻塞水位。seq2 已 DEAD（人工待处理），MIN(NEW)=3 → 水位 = 2
        assertThat(d.safeSeq(TASK_ID, GROUP_NO)).isEqualTo(2L);
    }

    @Test
    @DisplayName("should_countBacklogAndOldest：积压量与最老未应用时间可查")
    void should_countBacklogAndOldest() {
        ChangeLogDao d = freshDao();
        for (long seq = 1; seq <= 3; seq++) {
            d.insert(event(seq), null, null);
        }
        assertThat(d.backlog(TASK_ID)).isEqualTo(3L);
        assertThat(d.oldestUnappliedCommitTs(TASK_ID)).isNotNull();
    }

    @Test
    @DisplayName("should_rejectDuplicateSeqId：同 id 重复写入抛唯一键异常（不静默覆盖）")
    void should_rejectDuplicateSeqId() {
        ChangeLogDao d = freshDao();
        d.insert(event(1L), null, null);
        assertThat(d.pollBatch(TASK_ID, GROUP_NO, 1)).hasSize(1);
        // 同 id 再插一次 → 主键冲突
        Throwable thrown = null;
        try {
            d.insert(event(1L), null, null);
        } catch (RuntimeException e) {
            thrown = e;
        }
        assertThat(thrown).isNotNull();
        assertThat(d.backlog(TASK_ID)).isEqualTo(1L);
    }

    @Test
    @DisplayName("should_deleteAppliedBefore_onlyApplied：归档只删已应用，NEW 保留")
    void should_deleteAppliedBefore_onlyApplied() {
        ChangeLogDao d = freshDao();
        List<ChangeEvent> events = new ArrayList<ChangeEvent>();
        for (long seq = 1; seq <= 5; seq++) {
            events.add(event(seq));
        }
        d.insertBatch(events);
        d.markApplied(Arrays.asList(Long.valueOf(11L), Long.valueOf(21L)), ChangeStatus.APPLIED);
        int deleted = d.deleteAppliedBefore(Instant.now().plusSeconds(60), 100);
        assertThat(deleted).isEqualTo(2);
        assertThat(d.backlog(TASK_ID)).isEqualTo(3L);
    }
}
