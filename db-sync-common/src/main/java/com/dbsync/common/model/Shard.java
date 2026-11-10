package com.dbsync.common.model;

import com.dbsync.common.enums.RangeKind;
import com.dbsync.common.enums.ShardState;

/**
 * 分片（可变对象：状态、租约、进度由 DAO 更新）。
 *
 * <p>区间语义 {@code (pk_start, pk_end]}，见 {@link PkRange}。
 * 字符串形式的上下界存储是为了统一承载数值/字符主键，读取时按 {@link #getRangeKind()} 解析。
 */
public final class Shard {

    private final long id;
    private final long taskId;
    private final long tableMappingId;
    private final int shardNo;
    private final RangeKind rangeKind;
    private final String pkStart;
    private final String pkEnd;
    private ShardState state;
    private String leaseOwner;
    private String lastPk;
    private long committedRows;
    private int attempt;
    private int version;

    /**
     * 构造。
     *
     * @param id             分片 ID
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @param shardNo        任务内全局序号
     * @param rangeKind      区间类型
     * @param pkStart        下界（不含），null 表示无界
     * @param pkEnd          上界（含），null 表示无界
     * @param state          状态
     * @param leaseOwner     租约持有者
     * @param lastPk         已提交的最后主键
     * @param committedRows  已提交行数
     * @param attempt        已尝试次数
     * @param version        乐观锁版本
     */
    public Shard(long id, long taskId, long tableMappingId, int shardNo, RangeKind rangeKind,
                 String pkStart, String pkEnd, ShardState state, String leaseOwner,
                 String lastPk, long committedRows, int attempt, int version) {
        this.id = id;
        this.taskId = taskId;
        this.tableMappingId = tableMappingId;
        this.shardNo = shardNo;
        this.rangeKind = rangeKind == null ? RangeKind.NUMERIC : rangeKind;
        this.pkStart = pkStart;
        this.pkEnd = pkEnd;
        this.state = state == null ? ShardState.PENDING : state;
        this.leaseOwner = leaseOwner;
        this.lastPk = lastPk;
        this.committedRows = committedRows;
        this.attempt = attempt;
        this.version = version;
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

    /** @return 下界（不含） */
    public String getPkStart() {
        return pkStart;
    }

    /** @return 上界（含） */
    public String getPkEnd() {
        return pkEnd;
    }

    /** @return 状态 */
    public ShardState getState() {
        return state;
    }

    /**
     * 设置状态。
     *
     * @param state 新状态
     */
    public void setState(ShardState state) {
        this.state = state;
    }

    /** @return 租约持有者 */
    public String getLeaseOwner() {
        return leaseOwner;
    }

    /**
     * 设置租约持有者。
     *
     * @param leaseOwner 实例 ID
     */
    public void setLeaseOwner(String leaseOwner) {
        this.leaseOwner = leaseOwner;
    }

    /** @return 最后提交的主键 */
    public String getLastPk() {
        return lastPk;
    }

    /**
     * 设置最后提交的主键。
     *
     * @param lastPk 主键字符串
     */
    public void setLastPk(String lastPk) {
        this.lastPk = lastPk;
    }

    /** @return 已提交行数 */
    public long getCommittedRows() {
        return committedRows;
    }

    /**
     * 设置已提交行数。
     *
     * @param committedRows 行数
     */
    public void setCommittedRows(long committedRows) {
        this.committedRows = committedRows;
    }

    /** @return 已尝试次数 */
    public int getAttempt() {
        return attempt;
    }

    /**
     * 设置尝试次数。
     *
     * @param attempt 次数
     */
    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    /** @return 乐观锁版本 */
    public int getVersion() {
        return version;
    }

    /**
     * 设置乐观锁版本。
     *
     * @param version 版本
     */
    public void setVersion(int version) {
        this.version = version;
    }

    /**
     * @return 区间视图（按字符串承载；数值主键由调用方按需转换）
     */
    public PkRange toRange() {
        return new PkRange(pkStart, pkEnd, rangeKind);
    }

    @Override
    public String toString() {
        return "Shard#" + id + "[no=" + shardNo + "," + state + ",(" + pkStart + "," + pkEnd + "]]";
    }
}
