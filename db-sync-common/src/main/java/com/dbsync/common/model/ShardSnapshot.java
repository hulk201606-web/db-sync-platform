package com.dbsync.common.model;

import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.enums.ShardState;
import java.time.Instant;

/**
 * 分片快照（{@code sync_shard} 的只读投影，供"MIG-06 分片级进度"列表使用）。
 *
 * <p><b>为什么不直接用 {@link Shard}</b>：{@link Shard} 是执行期对象，
 * 只带调度必需的字段（不含 {@code source_rows/last_error/started_at/finished_at/
 * lease_expire_at}）；为了看板把执行期对象撑大，会让 worker 路径多读无用列。
 * 本类是"只读、不可变"的投影，字段与前端热力图所需一一对应。
 *
 * <p>时间字段为 {@link Instant}（UTC），由 API 层格式化为 ISO-8601 字符串。
 */
public final class ShardSnapshot {

    private final long id;
    private final long taskId;
    private final long tableMappingId;
    private final int shardNo;
    private final RangeKind rangeKind;
    private final String pkStart;
    private final String pkEnd;
    private final ShardState state;
    private final String leaseOwner;
    private final Instant leaseExpireAt;
    private final String lastPk;
    private final long committedRows;
    private final long sourceRows;
    private final int attempt;
    private final String lastError;
    private final Instant startedAt;
    private final Instant finishedAt;

    /**
     * 构造。
     *
     * @param id             分片 ID
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @param shardNo        分片号
     * @param rangeKind      区间类型
     * @param pkStart        主键起点（可 null）
     * @param pkEnd          主键终点（可 null）
     * @param state          状态
     * @param leaseOwner     租约持有者（可 null）
     * @param leaseExpireAt  租约到期时间（可 null）
     * @param committedRows  已提交行数
     * @param sourceRows     源侧行数（-1 表示未知）
     * @param attempt        已尝试次数
     * @param lastError      最近错误摘要（可 null）
     * @param startedAt      开始时间（可 null）
     * @param finishedAt     结束时间（可 null）
     */
    public ShardSnapshot(long id, long taskId, long tableMappingId, int shardNo,
                         RangeKind rangeKind, String pkStart, String pkEnd, ShardState state,
                         String leaseOwner, Instant leaseExpireAt, String lastPk,
                         long committedRows, long sourceRows, int attempt, String lastError,
                         Instant startedAt, Instant finishedAt) {
        this.id = id;
        this.taskId = taskId;
        this.tableMappingId = tableMappingId;
        this.shardNo = shardNo;
        this.rangeKind = rangeKind;
        this.pkStart = pkStart;
        this.pkEnd = pkEnd;
        this.state = state;
        this.leaseOwner = leaseOwner;
        this.leaseExpireAt = leaseExpireAt;
        this.lastPk = lastPk;
        this.committedRows = committedRows;
        this.sourceRows = sourceRows;
        this.attempt = attempt;
        this.lastError = lastError;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    /** @return 分片 ID */
    public long getId() {
        return id;
    }

    /** @return 任务 ID */
    public long getTaskId() {
        return taskId;
    }

    /** @return 表映射 ID */
    public long getTableMappingId() {
        return tableMappingId;
    }

    /** @return 分片号 */
    public int getShardNo() {
        return shardNo;
    }

    /** @return 区间类型 */
    public RangeKind getRangeKind() {
        return rangeKind;
    }

    /** @return 主键起点（可 null） */
    public String getPkStart() {
        return pkStart;
    }

    /** @return 主键终点（可 null） */
    public String getPkEnd() {
        return pkEnd;
    }

    /** @return 状态 */
    public ShardState getState() {
        return state;
    }

    /** @return 租约持有者（可 null） */
    public String getLeaseOwner() {
        return leaseOwner;
    }

    /** @return 租约到期时间（可 null） */
    public Instant getLeaseExpireAt() {
        return leaseExpireAt;
    }

    /** @return 断点主键（可 null，恢复位置） */
    public String getLastPk() {
        return lastPk;
    }

    /** @return 已提交行数 */
    public long getCommittedRows() {
        return committedRows;
    }

    /** @return 源侧行数（-1 表示未知） */
    public long getSourceRows() {
        return sourceRows;
    }

    /** @return 已尝试次数 */
    public int getAttempt() {
        return attempt;
    }

    /** @return 最近错误摘要（可 null） */
    public String getLastError() {
        return lastError;
    }

    /** @return 开始时间（可 null） */
    public Instant getStartedAt() {
        return startedAt;
    }

    /** @return 结束时间（可 null） */
    public Instant getFinishedAt() {
        return finishedAt;
    }

    @Override
    public String toString() {
        return "ShardSnapshot[task=" + taskId + ",no=" + shardNo + "," + state
                + ",rows=" + committedRows + "/" + sourceRows + "]";
    }
}
