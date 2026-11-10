package com.dbsync.core.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.enums.ScopeType;
import com.dbsync.common.enums.ShardState;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.model.Checkpoint;
import com.dbsync.common.model.Shard;
import com.dbsync.common.util.SnowflakeId;
import com.dbsync.core.CoreTestSupport;
import com.dbsync.core.write.WriteResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T21 验收（★ I1 的防线）：未提交的写入绝不允许推进断点。
 */
class CheckpointStoreTest extends CoreTestSupport {

    private static final long TASK_ID = 1001L;
    private static final long MAPPING_ID = 55L;
    private static final int SHARD_NO = 3;

    private CheckpointStore store;
    private long shardId;

    /**
     * 装配 store + 一个真实分片（SHARD scope 会同步写 sync_shard）。
     */
    @BeforeEach
    void setUpStore() {
        store = new CheckpointStore(checkpointDao, shardDao, watermarkDao, tx);
        shardId = new SnowflakeId(1L).nextId();
        shardDao.insertBatch(List.of(new Shard(shardId, TASK_ID, MAPPING_ID, SHARD_NO,
                RangeKind.NUMERIC, "0", "999", ShardState.RUNNING, "inst-A", null, 0L, 1, 0)));
    }

    @Test
    @DisplayName("should_throw_When_writeNotCommitted ★：未提交时抛 IllegalStateException 且库无变化")
    void should_throw_When_writeNotCommitted() {
        Checkpoint ckpt = Checkpoint.initial(TASK_ID, MAPPING_ID, ScopeType.SHARD, SHARD_NO)
                .withLastPk("100").withCommitted(100L);
        WriteResult notCommitted = WriteResult.beforeCommit(100L, 12L);
        assertThatThrownBy(() -> store.save(ckpt, notCommitted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V1");
        // ★ 元数据库必须没有任何变化
        assertThat(checkpointDao.load(TASK_ID, MAPPING_ID, ScopeType.SHARD, SHARD_NO))
                .isEmpty();
        assertThat(shardDao.findById(shardId).orElseThrow().getLastPk()).isNull();
    }

    @Test
    @DisplayName("should_throw_When_writeResultNull：null 写结果同样被拒（不许绕过守卫）")
    void should_throw_When_writeResultNull() {
        Checkpoint ckpt = Checkpoint.initial(TASK_ID, MAPPING_ID, ScopeType.SHARD, 1);
        assertThatThrownBy(() -> store.save(ckpt, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("should_updateBothTables_When_saveSucceeded：checkpoint 与 shard.last_pk 同时更新")
    void should_updateBothTables_When_saveSucceeded() {
        Checkpoint ckpt = Checkpoint.initial(TASK_ID, MAPPING_ID, ScopeType.SHARD, SHARD_NO)
                .withLastPk("500").withCommitted(500L);
        store.save(ckpt, WriteResult.afterCommit(500L, 30L));

        Checkpoint saved = checkpointDao.load(TASK_ID, MAPPING_ID, ScopeType.SHARD,
                SHARD_NO).orElseThrow();
        assertThat(saved.getCommittedRows()).isEqualTo(500L);
        assertThat(saved.getLastPk()).isEqualTo("500");
        Shard shard = shardDao.findById(shardId).orElseThrow();
        assertThat(shard.getLastPk()).isEqualTo("500");
        assertThat(shard.getCommittedRows()).isEqualTo(500L);
    }

    @Test
    @DisplayName("should_rejectBackward_When_lastPkRegressed：last_pk 100 → 90 被拒（E3005）")
    void should_rejectBackward_When_lastPkRegressed() {
        Checkpoint first = Checkpoint.initial(TASK_ID, MAPPING_ID, ScopeType.SHARD,
                SHARD_NO).withLastPk("100").withCommitted(100L);
        store.save(first, WriteResult.afterCommit(100L, 10L));

        Checkpoint backward = Checkpoint.initial(TASK_ID, MAPPING_ID, ScopeType.SHARD,
                SHARD_NO).withLastPk("90").withCommitted(90L);
        assertThatThrownBy(() -> store.save(backward, WriteResult.afterCommit(90L, 10L)))
                .isInstanceOf(DbSyncException.class)
                .satisfies(e -> assertThat(((DbSyncException) e).errorCode().code())
                        .isEqualTo("E3005"));
        assertThat(checkpointDao.load(TASK_ID, MAPPING_ID, ScopeType.SHARD, SHARD_NO)
                .orElseThrow().getLastPk()).isEqualTo("100");
    }

    @Test
    @DisplayName("should_allowForwardProgress：last_pk 100 → 200 正常推进")
    void should_allowForwardProgress() {
        store.save(Checkpoint.initial(TASK_ID, MAPPING_ID, ScopeType.SHARD, SHARD_NO)
                .withLastPk("100").withCommitted(100L), WriteResult.afterCommit(100L, 10L));
        store.save(Checkpoint.initial(TASK_ID, MAPPING_ID, ScopeType.SHARD, SHARD_NO)
                .withLastPk("200").withCommitted(200L), WriteResult.afterCommit(100L, 10L));
        assertThat(checkpointDao.load(TASK_ID, MAPPING_ID, ScopeType.SHARD, SHARD_NO)
                .orElseThrow().getCommittedRows()).isEqualTo(200L);
    }

    @Test
    @DisplayName("should_readBack_When_reload：保存后可原样读回")
    void should_readBack_When_reload() {
        Checkpoint ckpt = Checkpoint.initial(TASK_ID, MAPPING_ID, ScopeType.SHARD, SHARD_NO)
                .withLastPk("999").withCommitted(999L);
        store.save(ckpt, WriteResult.afterCommit(999L, 5L));
        Checkpoint loaded = store.load(TASK_ID, MAPPING_ID, ScopeType.SHARD, SHARD_NO)
                .orElseThrow();
        assertThat(loaded.getLastPk()).isEqualTo("999");
        assertThat(loaded.getCommittedRows()).isEqualTo(999L);
        assertThat(loaded.getCommittedBatches()).isEqualTo(1L);
    }

    @Test
    @DisplayName("should_advanceWatermark_withoutWriteResult：水位推进不走守卫，且不回退")
    void should_advanceWatermark() {
        store.advanceWatermark(TASK_ID, 1, 50L, java.time.Instant.now());
        assertThat(watermarkDao.load(TASK_ID, 1).orElseThrow().getSafeSeq()).isEqualTo(50L);
        store.advanceWatermark(TASK_ID, 1, 40L, java.time.Instant.now());
        assertThat(watermarkDao.load(TASK_ID, 1).orElseThrow().getSafeSeq()).isEqualTo(50L);
    }
}
