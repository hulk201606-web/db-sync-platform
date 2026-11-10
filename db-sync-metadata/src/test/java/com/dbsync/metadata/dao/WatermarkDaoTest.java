package com.dbsync.metadata.dao;

import static org.assertj.core.api.Assertions.assertThat;

import com.dbsync.common.model.Watermark;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 组水位 DAO 测试：组独占（同一组同一时刻只能一个消费者）+ 水位只进不退。
 */
class WatermarkDaoTest extends MetaDaoTestSupport {

    private static final long TASK_ID = 4401L;
    private static final int GROUP_NO = 5;

    @Test
    @DisplayName("should_acquireExclusiveOwnership：第二次抢占失败，租约过期后可被抢")
    void should_acquireExclusiveOwnership() {
        WatermarkDao dao = new WatermarkDao(jdbc);
        assertThat(dao.tryAcquire(TASK_ID, GROUP_NO, "inst-A", Duration.ofSeconds(30))).isTrue();
        assertThat(dao.tryAcquire(TASK_ID, GROUP_NO, "inst-B", Duration.ofSeconds(30))).isFalse();
        // 同 owner 可重入
        assertThat(dao.tryAcquire(TASK_ID, GROUP_NO, "inst-A", Duration.ofSeconds(30))).isTrue();
        // 手动把租约置为过期 → B 可抢
        jdbc.update("UPDATE sync_change_watermark SET owner_expire_at=? WHERE task_id=? "
                + "AND group_no=?", java.sql.Timestamp.from(Instant.now().minusSeconds(5)),
                TASK_ID, GROUP_NO);
        assertThat(dao.tryAcquire(TASK_ID, GROUP_NO, "inst-B", Duration.ofSeconds(30))).isTrue();
        assertThat(dao.load(TASK_ID, GROUP_NO).orElseThrow().getConsumerOwner()).isEqualTo("inst-B");
    }

    @Test
    @DisplayName("should_advanceOnlyForward：advance 不允许回退水位")
    void should_advanceOnlyForward() {
        WatermarkDao dao = new WatermarkDao(jdbc);
        dao.ensureExists(TASK_ID, GROUP_NO);
        assertThat(dao.advance(TASK_ID, GROUP_NO, 100L, Instant.now(), 120L)).isEqualTo(1);
        assertThat(dao.advance(TASK_ID, GROUP_NO, 90L, Instant.now(), 100L)).isZero();
        assertThat(dao.load(TASK_ID, GROUP_NO).orElseThrow().getSafeSeq()).isEqualTo(100L);
        assertThat(dao.advance(TASK_ID, GROUP_NO, 150L, Instant.now(), 30L)).isEqualTo(1);
        Watermark wm = dao.load(TASK_ID, GROUP_NO).orElseThrow();
        assertThat(wm.getSafeSeq()).isEqualTo(150L);
        assertThat(wm.getLagMs()).isEqualTo(30L);
    }

    @Test
    @DisplayName("should_releaseAndListFreeGroups：释放后进入空闲组列表")
    void should_releaseAndListFreeGroups() {
        WatermarkDao dao = new WatermarkDao(jdbc);
        dao.tryAcquire(TASK_ID, GROUP_NO, "inst-A", Duration.ofSeconds(30));
        dao.ensureExists(TASK_ID, 6);
        assertThat(dao.findFreeGroups(TASK_ID)).hasSize(1);
        assertThat(dao.release(TASK_ID, GROUP_NO, "inst-A")).isEqualTo(1);
        assertThat(dao.findFreeGroups(TASK_ID)).hasSize(2);
    }
}
