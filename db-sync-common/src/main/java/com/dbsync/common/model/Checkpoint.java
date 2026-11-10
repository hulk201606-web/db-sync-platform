package com.dbsync.common.model;

import com.dbsync.common.enums.ScopeType;
import java.time.Instant;
import java.util.Objects;

/**
 * 断点/水位（不可变）。统一承载 SHARD / GROUP / POLLER 三种 scope。
 *
 * <p>变更用 {@code withXxx()} 返回新实例，避免跨线程共享可变对象（架构 §1.7）。
 */
public final class Checkpoint {

    private final long taskId;
    private final long tableMappingId;
    private final ScopeType scopeType;
    private final int scopeNo;
    private final String lastPk;
    private final Instant lastCommitTs;
    private final Long lastSeq;
    private final Long lastLogId;
    private final long committedRows;
    private final long committedBatches;
    private final int version;

    /**
     * 构造。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID（0 = 任务级）
     * @param scopeType      scope 类型
     * @param scopeNo        scope 号
     * @param lastPk         存量：最后主键
     * @param lastCommitTs   增量：最后提交时间
     * @param lastSeq        增量：最后序号
     * @param lastLogId      触发器模式：最后日志 ID
     * @param committedRows  已提交行数
     * @param committedBatches 已提交批次数
     * @param version        乐观锁版本
     */
    public Checkpoint(long taskId, long tableMappingId, ScopeType scopeType, int scopeNo,
                      String lastPk, Instant lastCommitTs, Long lastSeq, Long lastLogId,
                      long committedRows, long committedBatches, int version) {
        this.taskId = taskId;
        this.tableMappingId = tableMappingId;
        this.scopeType = Objects.requireNonNull(scopeType, "scopeType");
        this.scopeNo = scopeNo;
        this.lastPk = lastPk;
        this.lastCommitTs = lastCommitTs;
        this.lastSeq = lastSeq;
        this.lastLogId = lastLogId;
        this.committedRows = committedRows;
        this.committedBatches = committedBatches;
        this.version = version;
    }

    /**
     * 新建（未保存）断点。
     *
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @param scopeType      scope 类型
     * @param scopeNo        scope 号
     * @return 空断点
     */
    public static Checkpoint initial(long taskId, long tableMappingId, ScopeType scopeType, int scopeNo) {
        return new Checkpoint(taskId, tableMappingId, scopeType, scopeNo,
                null, null, null, null, 0L, 0L, 0);
    }

    /** @return 任务 ID */
    public long getTaskId() {
        return taskId;
    }

    /** @return 表映射 ID */
    public long getTableMappingId() {
        return tableMappingId;
    }

    /** @return scope 类型 */
    public ScopeType getScopeType() {
        return scopeType;
    }

    /** @return scope 号 */
    public int getScopeNo() {
        return scopeNo;
    }

    /** @return 最后主键，可能为 null */
    public String getLastPk() {
        return lastPk;
    }

    /** @return 最后提交时间，可能为 null */
    public Instant getLastCommitTs() {
        return lastCommitTs;
    }

    /** @return 最后序号，可能为 null */
    public Long getLastSeq() {
        return lastSeq;
    }

    /** @return 最后日志 ID，可能为 null */
    public Long getLastLogId() {
        return lastLogId;
    }

    /** @return 已提交行数 */
    public long getCommittedRows() {
        return committedRows;
    }

    /** @return 已提交批次数 */
    public long getCommittedBatches() {
        return committedBatches;
    }

    /** @return 乐观锁版本 */
    public int getVersion() {
        return version;
    }

    /**
     * 派生：替换最后主键。
     *
     * @param pk 新主键
     * @return 新实例
     */
    public Checkpoint withLastPk(String pk) {
        return new Checkpoint(taskId, tableMappingId, scopeType, scopeNo, pk, lastCommitTs,
                lastSeq, lastLogId, committedRows, committedBatches, version);
    }

    /**
     * 派生：累加已提交行数并批次 +1。
     *
     * @param rows 本批行数
     * @return 新实例
     */
    public Checkpoint withCommitted(long rows) {
        return new Checkpoint(taskId, tableMappingId, scopeType, scopeNo, lastPk, lastCommitTs,
                lastSeq, lastLogId, committedRows + rows, committedBatches + 1, version);
    }

    /**
     * 派生：更新增量位点。
     *
     * @param seq      安全序号
     * @param commitTs 安全提交时间
     * @param logId    最后日志 ID（可为 null）
     * @return 新实例
     */
    public Checkpoint withPosition(long seq, Instant commitTs, Long logId) {
        return new Checkpoint(taskId, tableMappingId, scopeType, scopeNo, lastPk, commitTs,
                seq, logId, committedRows, committedBatches, version);
    }

    @Override
    public String toString() {
        return "Checkpoint[" + scopeType + "#" + scopeNo + ",lastPk=" + lastPk
                + ",rows=" + committedRows + "]";
    }
}
