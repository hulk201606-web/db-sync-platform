package com.dbsync.common.model;

import com.dbsync.common.enums.DeadLetterSourceType;
import com.dbsync.common.enums.DeadLetterStatus;

/**
 * 死信（可变对象：状态由重放/丢弃操作推进）。
 */
public final class DeadLetter {

    private final long id;
    private final long taskId;
    private final Long tableMappingId;
    private final DeadLetterSourceType sourceType;
    private final Long refId;
    private final String pkJson;
    private final String payloadJson;
    private final String errorCode;
    private final String errorMsg;
    private final String stackDigest;
    private DeadLetterStatus status;
    private int retry;

    /**
     * 构造。
     *
     * @param id             死信 ID
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID（可 null）
     * @param sourceType     来源类型
     * @param refId          关联 ID（分片 ID / 变更 ID）
     * @param pkJson         主键 JSON
     * @param payloadJson    原始数据快照（重放用）
     * @param errorCode      错误码
     * @param errorMsg       错误信息
     * @param stackDigest    异常栈摘要（分组用）
     * @param status         状态
     * @param retry          已重试次数
     */
    public DeadLetter(long id, long taskId, Long tableMappingId, DeadLetterSourceType sourceType,
                      Long refId, String pkJson, String payloadJson, String errorCode,
                      String errorMsg, String stackDigest, DeadLetterStatus status, int retry) {
        this.id = id;
        this.taskId = taskId;
        this.tableMappingId = tableMappingId;
        this.sourceType = sourceType;
        this.refId = refId;
        this.pkJson = pkJson;
        this.payloadJson = payloadJson;
        this.errorCode = errorCode;
        this.errorMsg = errorMsg;
        this.stackDigest = stackDigest;
        this.status = status == null ? DeadLetterStatus.OPEN : status;
        this.retry = retry;
    }

    /** @return 死信 ID */
    public long getId() {
        return id;
    }

    /** @return 任务 ID */
    public long getTaskId() {
        return taskId;
    }

    /** @return 表映射 ID */
    public Long getTableMappingId() {
        return tableMappingId;
    }

    /** @return 来源类型 */
    public DeadLetterSourceType getSourceType() {
        return sourceType;
    }

    /** @return 关联 ID */
    public Long getRefId() {
        return refId;
    }

    /** @return 主键 JSON */
    public String getPkJson() {
        return pkJson;
    }

    /** @return 数据快照 */
    public String getPayloadJson() {
        return payloadJson;
    }

    /** @return 错误码 */
    public String getErrorCode() {
        return errorCode;
    }

    /** @return 错误信息 */
    public String getErrorMsg() {
        return errorMsg;
    }

    /** @return 异常栈摘要 */
    public String getStackDigest() {
        return stackDigest;
    }

    /** @return 状态 */
    public DeadLetterStatus getStatus() {
        return status;
    }

    /**
     * 设置状态。
     *
     * @param status 新状态
     */
    public void setStatus(DeadLetterStatus status) {
        this.status = status;
    }

    /** @return 已重试次数 */
    public int getRetry() {
        return retry;
    }

    /**
     * 设置重试次数。
     *
     * @param retry 次数
     */
    public void setRetry(int retry) {
        this.retry = retry;
    }

    @Override
    public String toString() {
        return "DeadLetter#" + id + "[" + sourceType + "," + status + "," + errorCode + "]";
    }
}
