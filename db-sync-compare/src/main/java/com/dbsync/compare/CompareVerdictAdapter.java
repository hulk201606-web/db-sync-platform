package com.dbsync.compare;

import com.dbsync.metadata.dao.CompareJobDao;
import com.dbsync.core.port.CompareVerdictPort;
import com.dbsync.common.model.CompareVerdictSnapshot;
import java.util.Optional;

/**
 * 比对结论端口适配器（T36）：把 {@link CompareJobDao} 的"最近一次 DONE 作业"暴露给 cutover。
 *
 * <p>存在意义（架构 §3.1）：{@code db-sync-cutover} <b>不允许</b>依赖 {@code db-sync-compare}
 * （否则会形成 compare → core ← cutover 之外的环形依赖，且 Gate 会被比对引擎的实现细节污染）。
 * cutover 只依赖 {@link CompareVerdictPort}，本类在装配层（server）注入进去。
 *
 * <p><b>只返回"成功完成"的作业</b>：{@code state=FINISHED} 且 {@code finished_at} 非空。
 * 从未完成过比对时返回 {@code Optional.empty()} —— Gate 必须据此<b>拒绝</b>切流，
 * 绝不能把"没有结果"当成"通过"。
 */
public class CompareVerdictAdapter implements CompareVerdictPort {

    private final CompareJobDao compareJobDao;

    /**
     * 构造。
     *
     * @param compareJobDao 比对作业 DAO
     */
    public CompareVerdictAdapter(CompareJobDao compareJobDao) {
        this.compareJobDao = compareJobDao;
    }

    /**
     * 最近一次成功完成的比对结论。
     *
     * @param taskId 任务 ID
     * @return 结论快照；从未完成过返回 empty
     */
    @Override
    public Optional<CompareVerdictSnapshot> latestVerdict(long taskId) {
        return compareJobDao.latestFinishedSnapshot(taskId);
    }
}
