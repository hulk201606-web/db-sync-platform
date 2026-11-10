package com.dbsync.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbsync.common.enums.DiffType;
import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import com.dbsync.common.model.DiffRecord;
import com.dbsync.common.model.SyncTask;
import com.dbsync.common.model.TableMapping;
import com.dbsync.common.util.HashUtil;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T36 验收：差异修复的两条硬约束。
 *
 * <ol>
 *   <li>{@code fixToSource} 无理由 → {@code E8002}；</li>
 *   <li>{@code fixToSource} 必须先建 {@code bak_<table>_<ts>} 备份表，且内容为目标原值。</li>
 * </ol>
 *
 * <p>另外验证正向回放：{@code replayToTarget} 把源端当前值幂等写进目标，且 {@code ONLY_IN_TARGET}
 * 的行被跳过（源端没有该行，无法回放）。
 */
class DiffRepairerTest extends CompareTestSupport {

    /**
     * 装配修复器。
     *
     * @return 修复器
     */
    private DiffRepairer repairer() {
        return new DiffRepairer(taskDao, mappingDao, jobDao, diffDao, registry);
    }

    /**
     * 插一条差异记录。
     *
     * @param jobId   作业 ID
     * @param mapping 映射
     * @param pk      主键值
     * @param type    差异类型
     * @return 差异 ID
     */
    private long insertDiff(long jobId, TableMapping mapping, long pk, DiffType type) {
        long id = idGenerator.nextId();
        String pkJson = "{\"id\":" + pk + "}";
        DiffRecord record = new DiffRecord(id, jobId, 1L, mapping.getId(), pkJson,
                HashUtil.pkHash(List.of(Long.valueOf(pk))), type,
                Map.of("name", Map.of("src", "src-v", "tgt", "tgt-v")),
                "{\"id\":" + pk + ",\"name\":\"src-v\"}", "{\"id\":" + pk + ",\"name\":\"tgt-v\"}");
        diffDao.insertBatch(List.of(record));
        return id;
    }

    @Test
    @DisplayName("should_requireAdminAndReason_When_fixToSource：无理由 → E8002")
    void should_requireAdminAndReason_When_fixToSource() {
        SyncTask task = newTask();
        createBothTables("t_dr1");
        TableMapping mapping = newMapping(task.getId(), "t_dr1", 1);
        long jobId = newJob(task.getId(), Long.valueOf(mapping.getId()));
        long diffId = insertDiff(jobId, mapping, 1L, DiffType.VALUE_DIFF);

        DbSyncException e = assertThrows(DbSyncException.class,
                () -> repairer().fixToSource(jobId, List.of(Long.valueOf(diffId)), "alice", " "));
        assertEquals(ErrorCode.SECOND_CONFIRM_REQUIRED, e.errorCode());
    }

    @Test
    @DisplayName("should_reject_When_operatorNotAdmin：非 ADMIN → E8001")
    void should_reject_When_operatorNotAdmin() {
        SyncTask task = newTask();
        createBothTables("t_dr2");
        TableMapping mapping = newMapping(task.getId(), "t_dr2", 1);
        long jobId = newJob(task.getId(), Long.valueOf(mapping.getId()));
        long diffId = insertDiff(jobId, mapping, 1L, DiffType.VALUE_DIFF);

        DiffRepairer strict = new DiffRepairer(taskDao, mappingDao, jobDao, diffDao, registry,
                new com.dbsync.core.write.JdbcBatchWriter(), op -> "admin".equals(op));
        DbSyncException e = assertThrows(DbSyncException.class, () -> strict.fixToSource(jobId,
                List.of(Long.valueOf(diffId)), "bob", "因为要对齐"));
        assertEquals(ErrorCode.NO_PERMISSION, e.errorCode());
    }

    @Test
    @DisplayName("should_createBackupTable_When_fixToSource：备份表存在且为目标原值")
    void should_createBackupTable_When_fixToSource() {
        SyncTask task = newTask();
        createBothTables("t_dr3");
        insertRange(sourceDs, "t_dr3", 1, 1);
        insertRange(targetDs, "t_dr3", 1, 1);
        exec(sourceDs, "UPDATE t_dr3 SET name='source-value' WHERE id=1");
        exec(targetDs, "UPDATE t_dr3 SET name='target-value' WHERE id=1");
        TableMapping mapping = newMapping(task.getId(), "t_dr3", 1);
        long jobId = newJob(task.getId(), Long.valueOf(mapping.getId()));
        long diffId = insertDiff(jobId, mapping, 1L, DiffType.VALUE_DIFF);

        RepairResult result = repairer().fixToSource(jobId, List.of(Long.valueOf(diffId)),
                "admin", "目标端为准，业务已确认");

        assertNotNull(result.getBackupTable(), "必须生成备份表名");
        assertTrue(result.getBackupTable().startsWith("bak_t_dr3_"), result.getBackupTable());
        assertTrue(tableExists(targetDs, result.getBackupTable()), "备份表必须真实存在");
        assertEquals("target-value", queryString(targetDs,
                "SELECT name FROM " + result.getBackupTable() + " WHERE id=1"),
                "备份内容必须是目标原值");
        assertEquals("target-value", queryString(sourceDs, "SELECT name FROM t_dr3 WHERE id=1"),
                "源库必须被改成目标值");
        assertEquals(1, result.getApplied());
        assertEquals("FIXED_TO_SOURCE", jdbc.queryForObject(
                "SELECT repair_state FROM sync_compare_diff WHERE id=?",
                String.class, Long.valueOf(diffId)));
    }

    @Test
    @DisplayName("should_replayToTarget_When_sourceDiffers：源值幂等写回目标")
    void should_replayToTarget_When_sourceDiffers() {
        SyncTask task = newTask();
        createBothTables("t_dr4");
        insertRange(sourceDs, "t_dr4", 1, 3);
        insertRange(targetDs, "t_dr4", 1, 3);
        exec(sourceDs, "UPDATE t_dr4 SET name='correct' WHERE id=2");
        TableMapping mapping = newMapping(task.getId(), "t_dr4", 3);
        long jobId = newJob(task.getId(), Long.valueOf(mapping.getId()));
        long diffId = insertDiff(jobId, mapping, 2L, DiffType.VALUE_DIFF);

        RepairResult result = repairer().replayToTarget(jobId, List.of(Long.valueOf(diffId)));

        assertEquals(1, result.getApplied());
        assertEquals(0, result.getFailed());
        assertEquals("correct", queryString(targetDs, "SELECT name FROM t_dr4 WHERE id=2"));
        assertEquals(3L, countRows(targetDs, "t_dr4"), "回放不得多插行（幂等 Upsert）");
    }

    @Test
    @DisplayName("should_skipOnlyInTarget_When_replayToTarget：仅目标有的行无法正向回放")
    void should_skipOnlyInTarget_When_replayToTarget() {
        SyncTask task = newTask();
        createBothTables("t_dr5");
        insertRange(sourceDs, "t_dr5", 1, 1);
        insertRange(targetDs, "t_dr5", 1, 2);
        TableMapping mapping = newMapping(task.getId(), "t_dr5", 2);
        long jobId = newJob(task.getId(), Long.valueOf(mapping.getId()));
        long diffId = insertDiff(jobId, mapping, 2L, DiffType.ONLY_IN_TARGET);

        RepairResult result = repairer().replayToTarget(jobId, List.of(Long.valueOf(diffId)));

        assertEquals(0, result.getApplied());
        assertEquals(1, result.getSkipped());
        assertTrue(result.getMessages().get(0).contains("源端不存在"));
    }

    @Test
    @DisplayName("should_markReplayed_When_replaySuccess：差异状态落库为 REPLAYED")
    void should_markReplayed_When_replaySuccess() {
        SyncTask task = newTask();
        createBothTables("t_dr6");
        insertRange(sourceDs, "t_dr6", 1, 1);
        insertRange(targetDs, "t_dr6", 1, 1);
        exec(sourceDs, "UPDATE t_dr6 SET name='new' WHERE id=1");
        TableMapping mapping = newMapping(task.getId(), "t_dr6", 1);
        long jobId = newJob(task.getId(), Long.valueOf(mapping.getId()));
        long diffId = insertDiff(jobId, mapping, 1L, DiffType.VALUE_DIFF);

        repairer().replayToTarget(jobId, List.of(Long.valueOf(diffId)));

        assertEquals("REPLAYED", jdbc.queryForObject(
                "SELECT repair_state FROM sync_compare_diff WHERE id=?",
                String.class, Long.valueOf(diffId)));
    }
}
