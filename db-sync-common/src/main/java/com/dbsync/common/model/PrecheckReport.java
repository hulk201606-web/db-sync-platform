package com.dbsync.common.model;

import java.util.List;

/**
 * 预检报告（不可变）。{@code blocking} 非空即 {@link #hasBlocking()} 为 true，任务不得启动。
 */
public final class PrecheckReport {

    private final long taskId;
    private final List<String> blocking;
    private final List<String> warnings;

    /**
     * 构造。
     *
     * @param taskId   任务 ID
     * @param blocking 阻断项
     * @param warnings 警告项
     */
    public PrecheckReport(long taskId, List<String> blocking, List<String> warnings) {
        this.taskId = taskId;
        this.blocking = List.copyOf(blocking == null ? List.of() : blocking);
        this.warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    /** @return 任务 ID */
    public long getTaskId() {
        return taskId;
    }

    /** @return 不可变阻断项 */
    public List<String> getBlocking() {
        return blocking;
    }

    /** @return 不可变警告项 */
    public List<String> getWarnings() {
        return warnings;
    }

    /**
     * @return true 表示存在阻断项
     */
    public boolean hasBlocking() {
        return !blocking.isEmpty();
    }

    /**
     * @return true 表示预检通过
     */
    public boolean isPass() {
        return blocking.isEmpty();
    }

    @Override
    public String toString() {
        return "Precheck[task=" + taskId + ",blocking=" + blocking.size()
                + ",warnings=" + warnings.size() + "]";
    }
}
