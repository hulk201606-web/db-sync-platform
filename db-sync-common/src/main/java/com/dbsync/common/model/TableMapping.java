package com.dbsync.common.model;

import com.dbsync.common.enums.ChangeOp;
import com.dbsync.common.enums.ConflictPolicy;
import java.util.Objects;

/**
 * 表映射（可变对象：DDL 生成、覆盖状态由 DAO 更新）。
 *
 * <p><b>冲突策略（CDC-06）</b>：{@link #getConflictPolicy()} 是<b>表级</b>策略，
 * 来源 {@code sync_table_mapping.conflict_policy}（V3 迁移新增，默认 {@code SOURCE_WINS}）。
 * 优先级：表级 → 任务级（{@code sync_task.conflict_policy}）→ {@code SOURCE_WINS}。
 */
public final class TableMapping {

    private final long id;
    private final long taskId;
    private final String sourceSchema;
    private final String sourceTable;
    private final String targetSchema;
    private final String targetTable;
    private final boolean hasPrimaryKey;
    private final String rangeKind;
    private final long rowCountEstimate;
    private String generatedDdl;
    private boolean ddlApplied;
    private boolean enabled;
    private ConflictPolicy conflictPolicy;

    /**
     * 构造（表级冲突策略取默认 {@code SOURCE_WINS}）。
     *
     * <p>保留这个 12 参构造是为了不破坏既有调用点（30+ 处测试与 {@code MappingService}）；
     * 需要显式策略时用 13 参构造或 {@link #setConflictPolicy(ConflictPolicy)}。
     *
     * @param id              映射 ID
     * @param taskId          任务 ID
     * @param sourceSchema    源 schema
     * @param sourceTable     源表
     * @param targetSchema    目标 schema
     * @param targetTable     目标表
     * @param hasPrimaryKey   源表是否有主键
     * @param rangeKind       区间类型
     * @param rowCountEstimate 估算行数
     * @param generatedDdl    生成的 DDL
     * @param ddlApplied      目标表是否已建
     * @param enabled         是否参与同步
     */
    public TableMapping(long id, long taskId, String sourceSchema, String sourceTable,
                        String targetSchema, String targetTable, boolean hasPrimaryKey,
                        String rangeKind, long rowCountEstimate, String generatedDdl,
                        boolean ddlApplied, boolean enabled) {
        this(id, taskId, sourceSchema, sourceTable, targetSchema, targetTable, hasPrimaryKey,
                rangeKind, rowCountEstimate, generatedDdl, ddlApplied, enabled,
                ConflictPolicy.SOURCE_WINS);
    }

    /**
     * 构造。
     *
     * @param id              映射 ID
     * @param taskId          任务 ID
     * @param sourceSchema    源 schema
     * @param sourceTable     源表
     * @param targetSchema    目标 schema
     * @param targetTable     目标表
     * @param hasPrimaryKey   源表是否有主键
     * @param rangeKind       区间类型
     * @param rowCountEstimate 估算行数
     * @param generatedDdl    生成的 DDL
     * @param ddlApplied      目标表是否已建
     * @param enabled         是否参与同步
     * @param conflictPolicy  表级冲突策略（null → {@code SOURCE_WINS}）
     */
    public TableMapping(long id, long taskId, String sourceSchema, String sourceTable,
                        String targetSchema, String targetTable, boolean hasPrimaryKey,
                        String rangeKind, long rowCountEstimate, String generatedDdl,
                        boolean ddlApplied, boolean enabled, ConflictPolicy conflictPolicy) {
        this.id = id;
        this.taskId = taskId;
        this.sourceSchema = Objects.requireNonNull(sourceSchema, "sourceSchema");
        this.sourceTable = Objects.requireNonNull(sourceTable, "sourceTable");
        this.targetSchema = targetSchema;
        this.targetTable = Objects.requireNonNull(targetTable, "targetTable");
        this.hasPrimaryKey = hasPrimaryKey;
        this.rangeKind = rangeKind == null ? "NUMERIC" : rangeKind;
        this.rowCountEstimate = rowCountEstimate;
        this.generatedDdl = generatedDdl;
        this.ddlApplied = ddlApplied;
        this.enabled = enabled;
        this.conflictPolicy = conflictPolicy == null ? ConflictPolicy.SOURCE_WINS : conflictPolicy;
    }

    /** @return 映射 ID */
    public long getId() {
        return id;
    }

    /** @return 任务 ID */
    public long getTaskId() {
        return taskId;
    }

    /** @return 源 schema */
    public String getSourceSchema() {
        return sourceSchema;
    }

    /** @return 源表 */
    public String getSourceTable() {
        return sourceTable;
    }

    /** @return 目标 schema */
    public String getTargetSchema() {
        return targetSchema;
    }

    /** @return 目标表 */
    public String getTargetTable() {
        return targetTable;
    }

    /** @return 源表是否有主键 */
    public boolean isHasPrimaryKey() {
        return hasPrimaryKey;
    }

    /** @return 区间类型 */
    public String getRangeKind() {
        return rangeKind;
    }

    /** @return 估算行数 */
    public long getRowCountEstimate() {
        return rowCountEstimate;
    }

    /** @return 生成的 DDL */
    public String getGeneratedDdl() {
        return generatedDdl;
    }

    /**
     * 设置生成的 DDL。
     *
     * @param generatedDdl DDL
     */
    public void setGeneratedDdl(String generatedDdl) {
        this.generatedDdl = generatedDdl;
    }

    /** @return 目标表是否已建 */
    public boolean isDdlApplied() {
        return ddlApplied;
    }

    /**
     * 设置 DDL 是否已应用。
     *
     * @param ddlApplied true 表示已建表
     */
    public void setDdlApplied(boolean ddlApplied) {
        this.ddlApplied = ddlApplied;
    }

    /** @return 是否参与同步 */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置是否参与同步。
     *
     * @param enabled true 启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 表级冲突策略（CDC-06）。
     *
     * @return 策略（永不为 null）
     */
    public ConflictPolicy getConflictPolicy() {
        return conflictPolicy;
    }

    /**
     * 设置表级冲突策略。
     *
     * @param conflictPolicy 策略（null → {@code SOURCE_WINS}）
     */
    public void setConflictPolicy(ConflictPolicy conflictPolicy) {
        this.conflictPolicy = conflictPolicy == null ? ConflictPolicy.SOURCE_WINS : conflictPolicy;
    }

    @Override
    public String toString() {
        return "Mapping#" + id + "[" + sourceSchema + "." + sourceTable + " → "
                + targetTable + "]";
    }
}
