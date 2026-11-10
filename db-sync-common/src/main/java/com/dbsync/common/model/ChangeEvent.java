package com.dbsync.common.model;

import com.dbsync.common.enums.ChangeOp;
import com.dbsync.common.enums.ChangeStatus;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 变更事件（不可变）。一行 {@code sync_change_log} 的领域视图。
 */
public final class ChangeEvent {

    private final long id;
    private final long taskId;
    private final long tableMappingId;
    private final int groupNo;
    private final long pkHash;
    private final long seq;
    private final Instant commitTs;
    private final ChangeOp op;
    private final List<Object> pkTuple;
    private final Map<String, Object> before;
    private final Map<String, Object> after;
    private final List<String> changedColumns;
    private final ChangeStatus status;
    private final int retry;
    private final String errorMsg;
    private final String conflictAction;

    /**
     * 构造。
     *
     * @param id             日志行 ID
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @param groupNo        组号
     * @param pkHash         主键哈希
     * @param seq            组内序号
     * @param commitTs       提交时间
     * @param op             操作
     * @param pkTuple        主键元组
     * @param before         前像（可 null）
     * @param after          后像（可 null）
     * @param changedColumns 变更列
     * @param status         状态
     * @param retry          已重试次数
     * @param errorMsg       错误信息
     * @param conflictAction 冲突动作
     */
    public ChangeEvent(long id, long taskId, long tableMappingId, int groupNo, long pkHash, long seq,
                       Instant commitTs, ChangeOp op, List<Object> pkTuple,
                       Map<String, Object> before, Map<String, Object> after,
                       List<String> changedColumns, ChangeStatus status, int retry,
                       String errorMsg, String conflictAction) {
        this.id = id;
        this.taskId = taskId;
        this.tableMappingId = tableMappingId;
        this.groupNo = groupNo;
        this.pkHash = pkHash;
        this.seq = seq;
        this.commitTs = Objects.requireNonNull(commitTs, "commitTs");
        this.op = Objects.requireNonNull(op, "op");
        this.pkTuple = List.copyOf(pkTuple == null ? List.of() : pkTuple);
        this.before = before == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(before));
        this.after = after == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(after));
        this.changedColumns = List.copyOf(changedColumns == null ? List.of() : changedColumns);
        this.status = status == null ? ChangeStatus.NEW : status;
        this.retry = retry;
        this.errorMsg = errorMsg;
        this.conflictAction = conflictAction;
    }

    /**
     * 便捷工厂：新事件。
     *
     * @param id             日志行 ID
     * @param taskId         任务 ID
     * @param tableMappingId 表映射 ID
     * @param groupNo        组号
     * @param pkHash         主键哈希
     * @param seq            序号
     * @param commitTs       提交时间
     * @param op             操作
     * @param pkTuple        主键
     * @param after          后像
     * @return 事件
     */
    public static ChangeEvent ofNew(long id, long taskId, long tableMappingId, int groupNo, long pkHash,
                                    long seq, Instant commitTs, ChangeOp op, List<Object> pkTuple,
                                    Map<String, Object> after) {
        return new ChangeEvent(id, taskId, tableMappingId, groupNo, pkHash, seq, commitTs, op,
                pkTuple, null, after, null, ChangeStatus.NEW, 0, null, null);
    }

    /** @return 日志行 ID */
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

    /** @return 组号 */
    public int getGroupNo() {
        return groupNo;
    }

    /** @return 主键哈希 */
    public long getPkHash() {
        return pkHash;
    }

    /** @return 序号 */
    public long getSeq() {
        return seq;
    }

    /** @return 提交时间 */
    public Instant getCommitTs() {
        return commitTs;
    }

    /** @return 操作 */
    public ChangeOp getOp() {
        return op;
    }

    /** @return 不可变主键元组 */
    public List<Object> getPkTuple() {
        return pkTuple;
    }

    /** @return 不可变前像 */
    public Map<String, Object> getBefore() {
        return before;
    }

    /** @return 不可变后像 */
    public Map<String, Object> getAfter() {
        return after;
    }

    /** @return 不可变变更列 */
    public List<String> getChangedColumns() {
        return changedColumns;
    }

    /** @return 状态 */
    public ChangeStatus getStatus() {
        return status;
    }

    /** @return 已重试次数 */
    public int getRetry() {
        return retry;
    }

    /** @return 错误信息 */
    public String getErrorMsg() {
        return errorMsg;
    }

    /** @return 冲突动作 */
    public String getConflictAction() {
        return conflictAction;
    }

    /**
     * @return 不可变排序键（先 commitTs 后 seq）
     */
    public CommitSequence commitSequence() {
        return new CommitSequence(commitTs, seq);
    }

    /**
     * 派生：指定冲突动作。
     *
     * @param action 动作标记（如 {@code TARGET_WINS_IGNORED}）
     * @return 新实例
     */
    public ChangeEvent withConflictAction(String action) {
        return new ChangeEvent(id, taskId, tableMappingId, groupNo, pkHash, seq, commitTs, op,
                pkTuple, before, after, changedColumns, status, retry, errorMsg, action);
    }

    @Override
    public String toString() {
        return "Change#" + id + "[task=" + taskId + ",g=" + groupNo + ",seq=" + seq
                + "," + op + ",status=" + status + "]";
    }
}
