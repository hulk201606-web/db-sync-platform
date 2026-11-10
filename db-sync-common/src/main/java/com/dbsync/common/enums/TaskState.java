package com.dbsync.common.enums;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 同步任务状态机（架构 §9.1）。
 *
 * <pre>
 * DRAFT → READY → RUNNING ⇄ PAUSED
 *                    ├→ FINISHED
 *                    └→ FAILED → READY
 * FINISHED / PAUSED / DRAFT → ARCHIVED
 * </pre>
 */
public enum TaskState {

    /** 草稿。 */
    DRAFT,
    /** 配置完整且预检通过，可启动。 */
    READY,
    /** 运行中。 */
    RUNNING,
    /** 已暂停（可从断点恢复）。 */
    PAUSED,
    /** 失败（可修复后重跑）。 */
    FAILED,
    /** 完成。 */
    FINISHED,
    /** 已归档。 */
    ARCHIVED;

    /**
     * @return true 表示终态（需人工新建任务才能继续）
     */
    public boolean isTerminal() {
        return this == FINISHED || this == ARCHIVED;
    }

    /**
     * @return true 表示任务正在推进数据
     */
    public boolean isActive() {
        return this == RUNNING || this == PAUSED;
    }

    /**
     * 允许的下一状态（唯一数据源）。
     *
     * @return 不可变集合
     */
    public Set<TaskState> allowedNext() {
        switch (this) {
            case DRAFT:
                return Collections.unmodifiableSet(EnumSet.of(READY, ARCHIVED));
            case READY:
                return Collections.unmodifiableSet(EnumSet.of(RUNNING));
            case RUNNING:
                return Collections.unmodifiableSet(EnumSet.of(PAUSED, FINISHED, FAILED));
            case PAUSED:
                return Collections.unmodifiableSet(EnumSet.of(RUNNING, FAILED, ARCHIVED));
            case FAILED:
                return Collections.unmodifiableSet(EnumSet.of(READY));
            case FINISHED:
                return Collections.unmodifiableSet(EnumSet.of(ARCHIVED));
            case ARCHIVED:
            default:
                return Collections.emptySet();
        }
    }

    /**
     * 校验跃迁合法性。
     *
     * @param to 目标状态
     * @return true 合法
     */
    public boolean canTransitionTo(TaskState to) {
        return to != null && allowedNext().contains(to);
    }
}
