package com.dbsync.api.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.api.ApiTestSupport;
import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.enums.ShardState;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.model.Shard;
import com.dbsync.common.model.SyncTask;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 租约回收测试（T44 验收 1/2）：过期行被回收后回到 PENDING，且立即可被 {@code claimOne} 抢到。
 */
class LeaseReaperTest extends ApiTestSupport {

    @Test
    @DisplayName("过期租约被回收：state=PENDING 且 lease_owner=NULL")
    void should_reclaimExpired_When_leaseExpired() {
        long taskId = runningTask();
        long shardId = insertShard(taskId, 1, ShardState.RUNNING, "dead-instance");
        expireLease(shardId);
        LeaseReaperJob reaper = new LeaseReaperJob(instanceDao, taskDao, shardDao, "live-instance");

        int reclaimed = reaper.runOnce();

        assertEquals(1, reclaimed);
        Shard after = shardDao.findById(shardId).orElseThrow();
        assertEquals(ShardState.PENDING, after.getState(), "回收后必须回到 PENDING");
        assertEquals(null, after.getLeaseOwner(), "回收后必须清空租约持有者");
    }

    @Test
    @DisplayName("回收判据与 claimOne 一致：回收后 claim 立即可抢")
    void should_claimImmediately_After_reclaim() {
        long taskId = runningTask();
        long shardId = insertShard(taskId, 2, ShardState.RUNNING, "dead-instance");
        expireLease(shardId);
        new LeaseReaperJob(instanceDao, taskDao, shardDao, "live-instance").runOnce();

        Optional<Shard> claimed = shardDao.claimOne(taskId, "live-instance", Duration.ofSeconds(30L));

        assertTrue(claimed.isPresent(), "回收后必须能立刻抢到（判据一致）");
        assertEquals(shardId, claimed.get().getId());
        assertEquals(ShardState.CLAIMED, claimed.get().getState());
    }

    @Test
    @DisplayName("未过期的租约不会被回收")
    void should_notReclaim_When_leaseAlive() {
        long taskId = runningTask();
        long shardId = insertShard(taskId, 3, ShardState.RUNNING, "alive-instance");
        jdbc.update("UPDATE sync_shard SET lease_expire_at=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now().plusSeconds(60L)), shardId);

        int reclaimed = new LeaseReaperJob(instanceDao, taskDao, shardDao, "live-instance")
                .runOnce();

        assertEquals(0, reclaimed);
        Shard after = shardDao.findById(shardId).orElseThrow();
        assertEquals("alive-instance", after.getLeaseOwner());
    }

    @Test
    @DisplayName("只回收 RUNNING 任务的过期分片（PAUSED 任务不动）")
    void should_skipPausedTask() {
        long pausedTask = newTask(newDataSource("p-src", com.dbsync.common.enums.DbType.MYSQL),
                newDataSource("p-tgt", com.dbsync.common.enums.DbType.MYSQL));
        taskDao.transition(pausedTask, TaskState.DRAFT, TaskState.READY);
        taskDao.transition(pausedTask, TaskState.READY, TaskState.RUNNING);
        taskDao.transition(pausedTask, TaskState.RUNNING, TaskState.PAUSED);
        long shardId = insertShard(pausedTask, 4, ShardState.RUNNING, "dead-instance");
        expireLease(shardId);

        int reclaimed = new LeaseReaperJob(instanceDao, taskDao, shardDao, "live-instance")
                .runOnce();

        assertEquals(0, reclaimed, "暂停任务的分片留待任务恢复时处理");
        assertNotNull(shardDao.findById(shardId));
    }

    @Test
    @DisplayName("过期分片清单只看失联实例")
    void should_listExpiredByOwnerLiveness() {
        long taskId = runningTask();
        long shardId = insertShard(taskId, 5, ShardState.RUNNING, "ghost-instance");
        List<Shard> expired = new LeaseReaperJob(instanceDao, taskDao, shardDao, "live-instance")
                .listExpired(taskId, Instant.now());
        assertEquals(1, expired.size());
        assertEquals(shardId, expired.get(0).getId());
    }

    private long runningTask() {
        long sourceId = newDataSource("src-" + System.nanoTime(),
                com.dbsync.common.enums.DbType.MYSQL);
        long targetId = newDataSource("tgt-" + System.nanoTime(),
                com.dbsync.common.enums.DbType.MYSQL);
        long taskId = newTask(sourceId, targetId);
        taskDao.transition(taskId, TaskState.DRAFT, TaskState.READY);
        taskDao.transition(taskId, TaskState.READY, TaskState.RUNNING);
        SyncTask task = taskDao.findById(taskId).orElseThrow();
        assertEquals(TaskState.RUNNING, task.getState());
        return taskId;
    }

    private long insertShard(long taskId, int shardNo, ShardState state, String owner) {
        long shardId = idGenerator.nextId();
        Shard shard = new Shard(shardId, taskId, 1L, shardNo, RangeKind.NUMERIC, "1", "100",
                state, null, "0", 0L, 1, 0);
        shardDao.insertBatch(List.of(shard));
        jdbc.update("UPDATE sync_shard SET lease_owner=?, lease_expire_at=? WHERE id=?",
                owner, java.sql.Timestamp.from(Instant.now().minusSeconds(60L)), shardId);
        return shardId;
    }

    private void expireLease(long shardId) {
        jdbc.update("UPDATE sync_shard SET lease_expire_at=? WHERE id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60L)), shardId);
    }
}
