package com.dbsync.core.port;

import com.dbsync.common.model.CompareVerdictSnapshot;
import java.util.Optional;

/**
 * 比对结论端口（架构 §3.1：cutover 不能直接依赖 compare 模块，只能依赖本端口）。
 *
 * <p>实现方：{@code db-sync-compare} 的 {@code CompareVerdictAdapter}。
 */
public interface CompareVerdictPort {

    /**
     * 最近一次<b>成功完成</b>的比对结论。
     *
     * @param taskId 任务 ID
     * @return 结论快照；从未完成过返回 {@code Optional.empty()}（Gate 必须据此拒绝切流）
     */
    Optional<CompareVerdictSnapshot> latestVerdict(long taskId);
}
