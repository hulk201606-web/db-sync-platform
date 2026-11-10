package com.dbsync.cdc;

import com.dbsync.common.enums.ChangeStatus;
import com.dbsync.common.model.ChangeEvent;
import com.dbsync.metadata.dao.ChangeLogDao;
import java.util.List;

/**
 * 变更队列（{@code sync_change_log} 的薄封装）。
 *
 * <p><b>为什么队列落地在元数据库而不是内存</b>：消费者进程会重启、会扩容，
 * 内存队列一重启就丢；而变更日志本身就是"待办事项"，
 * 落库后 {@code seq} 单调、{@code status} 可查，天然支持断点续跑与无界积压。
 *
 * <p>本类只做"读写状态"的翻译，不含任何重试/策略逻辑（那是 {@code GroupWorker} 的事），
 * 这样重试语义改动时不需要碰 DAO 层。
 */
public class ChangeQueue {

    private final ChangeLogDao changeLogDao;

    /**
     * 构造。
     *
     * @param changeLogDao 变更日志 DAO
     */
    public ChangeQueue(ChangeLogDao changeLogDao) {
        this.changeLogDao = changeLogDao;
    }

    /**
     * 批量入队（同一组内 {@code seq} 由生产方式保证单调，见 {@code TriggerLogPoller}）。
     *
     * @param events 变更事件
     */
    public void enqueue(List<ChangeEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        changeLogDao.insertBatch(events);
    }

    /**
     * 拉取一批待应用变更（按 {@code seq} 升序，组内串行的前提）。
     *
     * @param taskId  任务 ID
     * @param groupNo 组号
     * @param limit   上限
     * @return 事件列表
     */
    public List<ChangeEvent> pollBatch(long taskId, int groupNo, int limit) {
        return changeLogDao.pollBatch(taskId, groupNo, limit);
    }

    /**
     * 批量标记已应用（{@code ChangeLogDao} 内部按 IN 分片）。
     *
     * @param ids 变更 ID
     */
    public void markApplied(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        changeLogDao.markApplied(ids, ChangeStatus.APPLIED);
    }

    /**
     * 批量标记"已跳过"（冲突策略判定为不应用时使用，与 FAILED 区分开）。
     *
     * @param ids            变更 ID
     * @param conflictAction 冲突动作（写回 {@code conflict_action}，供审计追溯）
     */
    public void markSkipped(List<Long> ids, String conflictAction) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        changeLogDao.markSkipped(ids, conflictAction);
    }

    /**
     * 标记死信（重试耗尽）。
     *
     * @param id    变更 ID
     * @param error 错误摘要
     */
    public void markDead(long id, String error) {
        changeLogDao.markDead(id, error);
    }

    /**
     * 标记待重试。
     *
     * @param id    变更 ID
     * @param error 错误摘要
     */
    public void markRetry(long id, String error) {
        changeLogDao.markRetry(id, error);
    }

    /**
     * 全任务积压量。
     *
     * @param taskId 任务 ID
     * @return 未应用条数
     */
    public long backlog(long taskId) {
        return changeLogDao.backlog(taskId);
    }

    /**
     * 单组积压量。
     *
     * @param taskId  任务 ID
     * @param groupNo 组号
     * @return 未应用条数
     */
    public long groupBacklog(long taskId, int groupNo) {
        return changeLogDao.groupBacklog(taskId, groupNo);
    }
}
