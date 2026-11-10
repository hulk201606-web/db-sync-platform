package com.dbsync.core.port;

/**
 * 审计端口。
 *
 * <p><b>硬约束（架构 §10）</b>：{@link #record(AuditEntry)} 的实现必须
 * "永不抛异常"——审计写库失败绝不能导致业务动作回滚（例如"切流已完成但审计写失败"
 * 时如果把异常抛给调用方，用户会看到失败而实际已切换，是最危险的不一致）。
 * 实现里必须 try/catch 全部异常并打 WARN。
 */
public interface AuditPort {

    /**
     * 记录审计。
     *
     * @param entry 审计条目
     */
    void record(AuditEntry entry);
}
