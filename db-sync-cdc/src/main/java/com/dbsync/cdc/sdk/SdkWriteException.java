package com.dbsync.cdc.sdk;

import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import java.util.List;

/**
 * STRICT 模式下的双写失败异常（含定位一条变更所需的全部要素）。
 *
 * <p>业务方 {@code catch (SdkWriteException e)} 后通常做两件事：
 * <ol>
 *   <li>回滚自己的本地事务（{@code e.getTaskId() / getTable() / getPkTuple()} 用于打日志与告警）；</li>
 *   <li>{@code e.getCause()} 拿到目标侧根因（写队列不可用 / 元数据库不可达等）。</li>
 * </ol>
 *
 * <p>错误码复用 {@link ErrorCode#RETRY_EXHAUSTED}：语义就是"重试耗尽"，
 * 且它本身标记为可重试，业务方若有自己的重试框架可以据此判断。
 */
public class SdkWriteException extends DbSyncException {

    private static final long serialVersionUID = 1L;

    private final long taskId;
    private final String table;
    private final List<Object> pkTuple;

    /**
     * 构造。
     *
     * @param taskId  同步任务 ID
     * @param table   表名
     * @param pkTuple 主键元组（可为空列表，不允许 null）
     * @param attempts 实际尝试次数
     * @param cause   最后一次失败的根因（可 null）
     */
    public SdkWriteException(long taskId, String table, List<Object> pkTuple, int attempts,
                             Throwable cause) {
        super(ErrorCode.RETRY_EXHAUSTED, cause,
                "表=" + table + " 主键=" + String.valueOf(pkTuple) + " 尝试=" + attempts);
        this.taskId = taskId;
        this.table = table;
        this.pkTuple = pkTuple == null ? List.of() : List.copyOf(pkTuple);
        with("taskId", Long.valueOf(taskId));
        with("table", table);
        with("pk", this.pkTuple);
        with("attempts", Integer.valueOf(attempts));
    }

    /** @return 同步任务 ID */
    public long getTaskId() {
        return taskId;
    }

    /** @return 表名 */
    public String getTable() {
        return table;
    }

    /** @return 不可变主键元组 */
    public List<Object> getPkTuple() {
        return pkTuple;
    }
}
