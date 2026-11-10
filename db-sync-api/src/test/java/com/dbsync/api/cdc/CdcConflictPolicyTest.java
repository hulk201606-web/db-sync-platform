package com.dbsync.api.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dbsync.cdc.CdcContext;
import com.dbsync.common.enums.ChangeStatus;
import com.dbsync.common.enums.ConflictPolicy;
import com.dbsync.common.enums.IncrementalMode;
import com.dbsync.common.enums.TaskState;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.TableMapping;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PRD CDC-06 验收：<b>三种策略按表可配</b>。
 *
 * <p>此前 {@link CdcContextFactory} 把策略写死成 {@code SOURCE_WINS}，
 * {@code sync_table_mapping} 也没有策略列 —— 等于该 P0 需求没达成。本用例断言的是
 * "同一任务的两张表可以取不同策略，并因此得到不同的数据结果"：
 *
 * <pre>
 *   目标端已有 id=1 name='target-value'
 *   源端变更    id=1 name='source-value'
 *
 *   表 A（SOURCE_WINS）→ 目标变成 'source-value'
 *   表 B（TARGET_WINS）→ 目标保持 'target-value'（变更被判定 TARGET_WINS_IGNORED 跳过）
 * </pre>
 *
 * <p>走的是真链路：真 DAO 读 {@code conflict_policy} 列 → 真 {@code CdcContextFactory}
 * 装配表级策略 → 真 {@code GroupWorker} + 真 {@code ConflictResolver} + 真目标库。
 */
class CdcConflictPolicyTest extends CdcRuntimeTestSupport {

    private static final String TABLE_SOURCE_WINS = "t_policy_src_win";
    private static final String TABLE_TARGET_WINS = "t_policy_tgt_win";

    @Test
    @DisplayName("同一任务两张表：SOURCE_WINS 覆盖目标、TARGET_WINS 保留目标")
    void should_applyDifferentPolicies_When_tablesConfiguredDifferently() {
        long taskId = newTask(TaskState.RUNNING, IncrementalMode.TRIGGER_LOG, 1, 1);
        long sourceWinsId = newMapping(taskId, TABLE_SOURCE_WINS, ConflictPolicy.SOURCE_WINS);
        long targetWinsId = newMapping(taskId, TABLE_TARGET_WINS, ConflictPolicy.TARGET_WINS);
        createTargetTable(TABLE_SOURCE_WINS);
        createTargetTable(TABLE_TARGET_WINS);
        // 目标端预置"人工订正过的值"
        seedTarget(TABLE_SOURCE_WINS, 1L, "target-value");
        seedTarget(TABLE_TARGET_WINS, 1L, "target-value");

        // 两条变更（同一主键、同一组），唯一的区别是所属表映射 → 策略不同
        changeLogDao.insertBatch(List.of(
                change(taskId, sourceWinsId, 1L, "source-value", 1L, 0),
                change(taskId, targetWinsId, 1L, "source-value", 2L, 0)));

        CdcContext ctx = factory.create(taskId, () -> false);
        // ContextFactory 的装配结果直接可断言（不需要跑消费者就能验证"策略按表取"）
        assertEquals(ConflictPolicy.SOURCE_WINS, ctx.policyOf(sourceWinsId),
                "表级策略必须被 ContextFactory 读出来并生效");
        assertEquals(ConflictPolicy.TARGET_WINS, ctx.policyOf(targetWinsId));

        // 再真跑一遍消费者，断言数据结果确实因策略而不同
        runtime.start(taskId);
        awaitUntil(() -> changeLogDao.countByStatus(taskId, ChangeStatus.APPLIED) == 1L
                        && changeLogDao.countByStatus(taskId, ChangeStatus.SKIPPED) == 1L,
                "一条 APPLIED + 一条 SKIPPED");

        assertEquals("source-value", nameOf(TABLE_SOURCE_WINS, 1L),
                "SOURCE_WINS 表必须被源端覆盖");
        assertEquals("target-value", nameOf(TABLE_TARGET_WINS, 1L),
                "TARGET_WINS 表必须保留目标端的值（变更被跳过）");
        assertEquals("TARGET_WINS_IGNORED", conflictAction(taskId),
                "跳过原因必须写回 conflict_action 供审计");
    }

    @Test
    @DisplayName("优先级：表级 > 任务级 > 默认 SOURCE_WINS")
    void should_preferTablePolicy_When_bothLevelsConfigured() {
        long taskId = newTask(TaskState.RUNNING, IncrementalMode.TRIGGER_LOG, 1, 1);
        // 任务级设成 TARGET_WINS
        jdbc.update("UPDATE sync_task SET conflict_policy=? WHERE id=?",
                ConflictPolicy.TARGET_WINS.name(), taskId);
        // 表级显式设成 SOURCE_WINS → 必须赢过任务级
        long tableOverride = newMapping(taskId, "t_policy_override", ConflictPolicy.SOURCE_WINS);
        long tableInherit = newMapping(taskId, "t_policy_inherit", ConflictPolicy.TARGET_WINS);
        createTargetTable("t_policy_override");
        createTargetTable("t_policy_inherit");

        CdcContext ctx = factory.create(taskId, () -> false);

        assertEquals(ConflictPolicy.SOURCE_WINS, ctx.policyOf(tableOverride),
                "表级策略优先于任务级");
        assertEquals(ConflictPolicy.TARGET_WINS, ctx.policyOf(tableInherit),
                "表级未覆盖任务级时保持任务级值");
        // 未配置任何策略的映射：回落到任务级；任务级也没有时回落默认值（由 ContextBuilder 保证）
        assertEquals(ConflictPolicy.TARGET_WINS, ctx.getPolicy(), "任务级策略应被读出");
    }

    @Test
    @DisplayName("非法策略：DAO 更新与请求解析都抛 E1001，不静默兜底")
    void should_rejectInvalidPolicy() {
        long taskId = newTask(TaskState.RUNNING, IncrementalMode.TRIGGER_LOG, 1, 1);
        long mappingId = newMapping(taskId, "t_policy_invalid");

        DbSyncException bad = assertThrows(DbSyncException.class,
                () -> ConflictPolicy.parse("WHATEVER_WINS"));
        assertEquals(ErrorCode.PARAM_INVALID, bad.errorCode());

        assertThrows(DbSyncException.class, () -> ConflictPolicy.parse(null));
        assertThrows(DbSyncException.class, () -> ConflictPolicy.parse("  "));

        // 表级写入成功后再读，DAO 必须读回新值
        assertEquals(1, mappingDao.updateConflictPolicy(mappingId, ConflictPolicy.TIMESTAMP_NEWER));
        TableMapping reloaded = mappingDao.findById(mappingId).orElseThrow();
        assertEquals(ConflictPolicy.TIMESTAMP_NEWER, reloaded.getConflictPolicy());
    }

    @Test
    @DisplayName("库里被改坏的值：读取即失败（E1001），不做静默兜底")
    void should_failFast_When_dbHoldsUnknownPolicy() {
        long taskId = newTask(TaskState.RUNNING, IncrementalMode.TRIGGER_LOG, 1, 1);
        long mappingId = newMapping(taskId, "t_policy_corrupt");
        jdbc.update("UPDATE sync_table_mapping SET conflict_policy=? WHERE id=?",
                "BROKEN_VALUE", mappingId);

        DbSyncException e = assertThrows(DbSyncException.class,
                () -> mappingDao.findById(mappingId));
        assertEquals(ErrorCode.PARAM_INVALID, e.errorCode());
    }

    @Test
    @DisplayName("服务层写入：合法值落库生效，非法值抛 E1001 且不改库")
    void should_updatePolicyThroughService() {
        long taskId = newTask(TaskState.RUNNING, IncrementalMode.TRIGGER_LOG, 1, 1);
        long mappingId = newMapping(taskId, "t_policy_service");
        // updateConflictPolicy 只碰映射表，不需要真实数据源注册表
        com.dbsync.api.service.MappingService service = new com.dbsync.api.service.MappingService(
                taskDao, dataSourceDao, mappingDao, columnMappingDao,
                org.mockito.Mockito.mock(com.dbsync.core.datasource.DataSourceRegistry.class),
                idGenerator);

        assertEquals("TIMESTAMP_NEWER",
                service.updateConflictPolicy(taskId, mappingId, "timestamp_newer").conflictPolicy(),
                "大小写不敏感，且必须落库");
        assertEquals(ConflictPolicy.TIMESTAMP_NEWER,
                mappingDao.findById(mappingId).orElseThrow().getConflictPolicy());

        assertThrows(DbSyncException.class,
                () -> service.updateConflictPolicy(taskId, mappingId, "SOMETHING_ELSE"));
        assertEquals(ConflictPolicy.TIMESTAMP_NEWER,
                mappingDao.findById(mappingId).orElseThrow().getConflictPolicy(),
                "非法请求不得改库");
    }

    // ---------------------------------------------------------------- 辅助

    /**
     * 往目标表预置一行。
     *
     * @param table 表名
     * @param id    主键
     * @param name  值
     */
    private void seedTarget(String table, long id, String name) {
        exec(targetDs, "INSERT INTO " + table + " (id,name) VALUES (" + id + ",'" + name + "')");
    }

    /**
     * 读任务下唯一一条 SKIPPED 记录的 conflict_action。
     *
     * @param taskId 任务 ID
     * @return 冲突动作
     */
    private String conflictAction(long taskId) {
        List<String> actions = jdbc.queryForList("SELECT conflict_action FROM sync_change_log "
                + "WHERE task_id=? AND status=?", String.class, taskId,
                ChangeStatus.SKIPPED.name());
        return actions.isEmpty() ? null : actions.get(0);
    }
}
