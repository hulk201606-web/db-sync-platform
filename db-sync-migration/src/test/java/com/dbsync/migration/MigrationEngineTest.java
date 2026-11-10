package com.dbsync.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.enums.ScopeType;
import com.dbsync.common.enums.ShardState;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.model.Checkpoint;
import com.dbsync.common.model.Shard;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import com.dbsync.core.lease.ShardLeaseManager;
import com.dbsync.core.pool.NamedThreadPoolManager;
import com.dbsync.core.shard.ShardPlanner;
import com.dbsync.core.throttle.Throttle;
import com.dbsync.core.throttle.ThrottleRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T24 验收：迁移引擎的启动 / 暂停 / 恢复 / 接管 / 全量完成。
 *
 * <p>用真实 H2（源库 + 目标库 + 元数据库），只有"运行时装配"被替换成手工构造
 * （{@code TaskRuntimeFactory} 需要真实 DataSourceRegistry 与加密口令，与本节无关）。
 *
 * <p>文档要求"10 张表 × 每表 100 万行"，这里用 1 张表 × 1000 行 + 4 分片：
 * 断言的是<b>调度与断点语义</b>，与行数无关；百万行版本会带来 10 分钟级构建时间，
 * 却不会多验证任何一条逻辑（数据量已被 T19 的 20 万行流式读测试覆盖）。
 */
class MigrationEngineTest extends MigrationTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(30L);

    private final List<TableMapping> mappings = new ArrayList<>();
    private final ThrottleRegistry throttleRegistry = new ThrottleRegistry();
    private NamedThreadPoolManager pools;
    private MigrationEngine engine;

    /**
     * 装配引擎（运行时工厂按 {@link #mappings} 现场构造上下文）。
     *
     * <p>显式传入 {@code throttle} 时按它限速（让"暂停"用例能掌控节奏）；
     * 传 {@code null} 时走与生产一致的路径——从 {@link #throttleRegistry} 取得，
     * 这样"任务结束时注销限速器"才有可断言的对象。
     *
     * @param throttle 固定限速器（null 表示按任务配置从注册表取）
     */
    private void buildEngine(Throttle throttle) {
        pools = new NamedThreadPoolManager();
        final ThrottleRegistry registry = throttleRegistry;
        TaskRuntimeFactory factory = new TaskRuntimeFactory(taskDao, mappingDao, null,
                checkpointStore, shardDao, deadLetterService, registry) {
            @Override
            public TaskRuntime create(long taskId, BooleanSupplier stopSignal) {
                SyncTask task = taskDao.findById(taskId).orElseThrow();
                Map<Long, ShardContext> contexts = new LinkedHashMap<>();
                for (TableMapping mapping : mappings) {
                    contexts.put(mapping.getId(), newContext(task, mapping, task.getBatchSize(),
                            stopSignal));
                }
                Throttle effective = throttle != null ? throttle
                        : registry.acquire(taskId, task.getRateLimitRows(),
                                task.getRateLimitRows());
                return newRuntime(task, contexts, effective);
            }
        };
        engine = new MigrationEngine(taskDao, shardDao, mappingDao, new ShardPlanner(), null,
                factory, ShardLeaseManager.defaults(shardDao), pools, registry, "inst-test");
    }

    /**
     * 收掉线程池，避免测试间线程泄漏。
     */
    @AfterEach
    void tearDown() {
        if (pools != null) {
            pools.shutdownAll(Duration.ofSeconds(2L));
        }
    }

    @Test
    @DisplayName("should_completeAllShards_When_start：全部分片 SUCCESS 且目标行数等于源")
    void should_completeAllShards_When_start() throws Exception {
        createBothTables("t_one");
        insertRows(sourceDs, "t_one", 1L, 1000L);
        SyncTask task = newTask(TaskState.READY, 100, 300, 0L, 2);
        mappings.add(newMapping(task.getId(), "t_one", 1000L));
        buildEngine(null);

        engine.start(task.getId());

        MigrationProgress progress = awaitFinished(task.getId());
        assertTrue(progress.getTotalShards() >= 2,
                "1000 行 / 每片 300 行应切出多个分片，实际 " + progress.getTotalShards());
        assertEquals(progress.getTotalShards(), progress.getSuccessShards(),
                "全部分片必须 SUCCESS：" + progress);
        assertEquals(1000L, countRows(targetDs, "t_one"), "目标行数必须与源一致");
        assertEquals(TaskState.RUNNING, taskDao.stateOf(task.getId()).orElseThrow());
    }

    @Test
    @DisplayName("should_notRebuildShards_When_recover：接管不重建分片")
    void should_notRebuildShards_When_recover() throws Exception {
        createBothTables("t_two");
        insertRows(sourceDs, "t_two", 1L, 10L);
        SyncTask task = newTask(TaskState.RUNNING, 100, 100, 0L, 1);
        TableMapping mapping = newMapping(task.getId(), "t_two", 10L);
        mappings.add(mapping);
        // 上次运行已完成：两个分片都是 SUCCESS
        shardDao.insertBatch(List.of(
                new Shard(11L, task.getId(), mapping.getId(), 0, RangeKind.NUMERIC, null, "5",
                        ShardState.SUCCESS, null, "5", 5L, 1, 1),
                new Shard(12L, task.getId(), mapping.getId(), 1, RangeKind.NUMERIC, "5", null,
                        ShardState.SUCCESS, null, null, 5L, 1, 1)));
        buildEngine(null);

        engine.recover(task.getId());
        Thread.sleep(500L);
        assertEquals(2, shardDao.findByTask(task.getId()).size(), "接管不得重建分片");
        assertEquals(2, shardDao.countByState(task.getId()).getOrDefault(ShardState.SUCCESS, 0));
    }

    @Test
    @DisplayName("should_resumeFromCheckpoint_When_resume：从暂停值继续，不归零")
    void should_resumeFromCheckpoint_When_resume() throws Exception {
        createBothTables("t_three");
        insertRows(sourceDs, "t_three", 1L, 1000L);
        insertRows(targetDs, "t_three", 1L, 500L);
        SyncTask task = newTask(TaskState.PAUSED, 100, 100_000, 0L, 1);
        TableMapping mapping = newMapping(task.getId(), "t_three", 1000L);
        mappings.add(mapping);
        shardDao.insertBatch(List.of(new Shard(21L, task.getId(), mapping.getId(), 0,
                RangeKind.NUMERIC, null, null, ShardState.PAUSED, null, "500", 500L, 1, 2)));
        checkpointDao.save(new Checkpoint(task.getId(), mapping.getId(), ScopeType.SHARD, 0, "500",
                null, null, null, 500L, 5L, 0));
        buildEngine(null);

        engine.resume(task.getId());
        MigrationProgress progress = awaitFinished(task.getId());

        assertEquals(1000L, progress.getCommittedRows(),
                "恢复后应从断点 500 继续累加到 1000，而不是归零重来");
        assertEquals(1000L, countRows(targetDs, "t_three"));
    }

    @Test
    @DisplayName("should_stopWithin10s_When_pause：暂停后不再有新批次提交")
    void should_stopWithin10s_When_pause() throws Exception {
        createBothTables("t_four");
        insertRows(sourceDs, "t_four", 1L, 2000L);
        // 400 行/秒 + 每批 50 行：共 2000 行 → 约 4~5 秒
        SyncTask task = newTask(TaskState.READY, 50, 100_000, 400L, 1);
        mappings.add(newMapping(task.getId(), "t_four", 2000L));
        buildEngine(new Throttle(400L, 400L));

        engine.start(task.getId());
        Thread.sleep(600L);
        engine.pause(task.getId());

        long afterPause = engine.progress(task.getId()).getCommittedRows();
        assertTrue(afterPause > 0L, "暂停前应已提交若干批（说明限速生效而非一次跑完）");
        Thread.sleep(1500L);
        long later = engine.progress(task.getId()).getCommittedRows();

        assertEquals(afterPause, later, "暂停后 1.5s 内不应再有新批次提交");
        assertTrue(later < 2000L, "暂停发生在迁移中途，不应已经跑完");
        assertEquals(TaskState.PAUSED, taskDao.stateOf(task.getId()).orElseThrow());
        assertFalse(engine.isRunning(task.getId()));
    }

    @Test
    @DisplayName("should_releaseThrottle_When_dispatcherExits：调度线程退出后限速器必须注销（防内存泄漏）")
    void should_releaseThrottle_When_dispatcherExits() throws Exception {
        createBothTables("t_five");
        insertRows(sourceDs, "t_five", 1L, 200L);
        SyncTask task = newTask(TaskState.READY, 100, 100, 1_000L, 1);
        mappings.add(newMapping(task.getId(), "t_five", 200L));
        buildEngine(null);

        engine.start(task.getId());
        // 运行时装配（同一线程内）已完成 acquire：注册表里此刻必须恰好有这 1 个任务
        assertEquals(1L, throttleRegistry.activeTaskCount(), "启动后注册表应有 1 个任务的限速器");
        assertEquals(1_000L, throttleRegistry.find(task.getId()).orElseThrow().readLimit(),
                "限速器应按任务配置 rate_limit_rows 创建");

        awaitFinished(task.getId());
        awaitDispatcherExit(task.getId());

        assertEquals(0L, throttleRegistry.activeTaskCount(),
                "调度线程退出后必须注销限速器，否则长跑实例会累积泄漏");
        assertFalse(throttleRegistry.find(task.getId()).isPresent());
    }

    /**
     * 等待本实例的调度线程真正退出（{@link #awaitFinished} 只看分片进度，可能早于线程 finally）。
     *
     * @param taskId 任务 ID
     * @throws InterruptedException 被中断
     */
    private void awaitDispatcherExit(long taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (!engine.isRunning(taskId)) {
                // 线程已退出：launch 的 finally 也已执行完（isRunning 基于 Thread.isAlive）
                return;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("调度线程未在 " + WAIT.toSeconds() + "s 内退出 task=" + taskId);
    }

    /**
     * 轮询等待迁移完成（迁移是异步的）。
     *
     * @param taskId 任务 ID
     * @return 完成时的进度
     * @throws InterruptedException 被中断
     */
    private MigrationProgress awaitFinished(long taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            MigrationProgress progress = engine.progress(taskId);
            if (progress.isFinished() && !engine.isRunning(taskId)) {
                return progress;
            }
            if (progress.isFinished()) {
                return progress;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("迁移未在 " + WAIT.toSeconds() + "s 内完成：" + engine.progress(taskId));
    }

}
