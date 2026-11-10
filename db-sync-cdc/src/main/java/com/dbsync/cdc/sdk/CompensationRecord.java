package com.dbsync.cdc.sdk;

import java.time.Instant;
import java.util.List;

/**
 * 补偿表 {@code sync_sdk_outbox} 的一行记录（不可变）。
 *
 * <p><b>为什么补偿表放在业务侧数据库而不是平台元数据库</b>：
 * 补偿的唯一目的是"业务方写失败时还有一条本地痕迹"。如果它放在平台元数据库，
 * 那么"元数据库不可达"（恰恰是双写失败最常见的原因之一）时业务方连补偿都记不下来，
 * 补偿机制就退化成"第二个失败点"。因此 {@link JdbcCompensationStore} 的
 * {@code DataSource} 必须是<b>业务库</b>的，与业务本地事务同库。
 *
 * <p>{@code op} 取 {@code I/U/D} 单字符（与 {@code sync_change_log.op} 口径一致），
 * 分别对应插入/更新/删除；{@code U} 重放时按 upsert 语义处理（幂等）。
 */
public final class CompensationRecord {

    /** 操作码：插入。 */
    public static final String OP_INSERT = "I";
    /** 操作码：更新（含 upsert）。 */
    public static final String OP_UPDATE = "U";
    /** 操作码：删除。 */
    public static final String OP_DELETE = "D";

    private final long id;
    private final long taskId;
    private final String tableName;
    private final String op;
    private final String pkJson;
    private final String payload;
    private final CompensationStatus status;
    private final int retry;
    private final String lastError;
    private final Instant createdAt;
    private final Instant nextRetryAt;
    private final Instant replayedAt;

    /**
     * 构造。
     *
     * @param id          主键（补偿表自增/雪花，由写入方决定）
     * @param taskId      同步任务 ID
     * @param tableName   表名
     * @param op          操作码 {@code I/U/D}
     * @param pkJson      主键 JSON 数组（如 {@code [123]}）
     * @param payload     行数据 JSON 对象（删除时可为 null）
     * @param status      状态
     * @param retry       已重试次数
     * @param lastError   最近一次错误（≤1000 字符）
     * @param createdAt   创建时间
     * @param nextRetryAt 下次可重放时间（指数退避）
     * @param replayedAt  重放成功时间（可为 null）
     */
    public CompensationRecord(long id, long taskId, String tableName, String op, String pkJson,
                              String payload, CompensationStatus status, int retry,
                              String lastError, Instant createdAt, Instant nextRetryAt,
                              Instant replayedAt) {
        this.id = id;
        this.taskId = taskId;
        this.tableName = tableName;
        this.op = op;
        this.pkJson = pkJson;
        this.payload = payload;
        this.status = status == null ? CompensationStatus.PENDING : status;
        this.retry = retry;
        this.lastError = lastError;
        this.createdAt = createdAt;
        this.nextRetryAt = nextRetryAt;
        this.replayedAt = replayedAt;
    }

    /**
     * 新建一条待重放记录（id 由调用方分配）。
     *
     * @param id       主键
     * @param taskId   任务 ID
     * @param table    表名
     * @param op       操作码
     * @param pkJson   主键 JSON
     * @param payload  行数据 JSON（可 null）
     * @param lastError 首次失败原因
     * @param now      当前时间
     * @return 记录
     */
    public static CompensationRecord pending(long id, long taskId, String table, String op,
                                             String pkJson, String payload, String lastError,
                                             Instant now) {
        return new CompensationRecord(id, taskId, table, op, pkJson, payload,
                CompensationStatus.PENDING, 0, truncate(lastError), now, now, null);
    }

    /**
     * 错误信息截断（列宽 1000，超长会直接让补偿写入失败——那就本末倒置了）。
     *
     * @param raw 原始信息
     * @return 截断后的信息
     */
    static String truncate(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.length() <= 1000 ? raw : raw.substring(0, 1000);
    }

    /** @return 主键 */
    public long getId() {
        return id;
    }

    /** @return 任务 ID */
    public long getTaskId() {
        return taskId;
    }

    /** @return 表名 */
    public String getTableName() {
        return tableName;
    }

    /** @return 操作码 I/U/D */
    public String getOp() {
        return op;
    }

    /** @return 主键 JSON */
    public String getPkJson() {
        return pkJson;
    }

    /** @return 行数据 JSON */
    public String getPayload() {
        return payload;
    }

    /** @return 状态 */
    public CompensationStatus getStatus() {
        return status;
    }

    /** @return 已重试次数 */
    public int getRetry() {
        return retry;
    }

    /** @return 最近错误 */
    public String getLastError() {
        return lastError;
    }

    /** @return 创建时间 */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** @return 下次可重放时间 */
    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    /** @return 重放成功时间 */
    public Instant getReplayedAt() {
        return replayedAt;
    }

    /**
     * 主键 JSON 解析成元组（失败时返回空列表，调用方需自行判定）。
     *
     * @return 主键元组
     */
    public List<Object> pkTuple() {
        return com.dbsync.common.util.JsonUtil.parseArray(pkJson);
    }

    @Override
    public String toString() {
        return "CompensationRecord[id=" + id + ",task=" + taskId + ",table=" + tableName
                + ",op=" + op + ",status=" + status + ",retry=" + retry + "]";
    }
}
