package com.dbsync.api.security;

/**
 * 权限点（架构 §12.3 权限矩阵的行）。
 *
 * <p>本期只有 3 个角色、12 个动作（含"查看"这一类合并项），因此用"注解 + 枚举"而不用
 * Spring Security 的表达式（架构 §12.3 明确要求）。每个 {@link Role} 持有一个
 * {@code EnumSet<Permission>}，判定为纯内存操作，不查库。
 */
public enum Permission {

    /** 查看任务/大盘/报告/审计。 */
    TASK_VIEW("查看任务/大盘/报告/审计"),
    /** 创建/编辑任务与映射。 */
    TASK_EDIT("创建/编辑任务"),
    /** 启动/暂停/恢复/取消/重跑任务、预检、核数。 */
    TASK_EXEC("启动/暂停/恢复/取消/重跑任务"),
    /** 数据源新增/编辑/删除/连接测试。 */
    DS_MANAGE("数据源管理"),
    /** 发起比对、REPLAY_TO_TARGET 修复、差异下钻。 */
    COMPARE_RUN("发起比对与目标侧修复"),
    /** FIX_TO_SOURCE 反向修复（危险动作）。 */
    COMPARE_FIX_SOURCE("反向修复源库（FIX_TO_SOURCE）"),
    /** 查看切流计划/Gate/步骤。 */
    CUTOVER_VIEW("查看切流计划"),
    /** 切流推进。 */
    CUTOVER_ADVANCE("切流推进"),
    /** 切流回滚。 */
    CUTOVER_ROLLBACK("切流回滚"),
    /** 跳过 Gate（需三件套）。 */
    SKIP_GATE("跳过 Gate"),
    /** 死信重放/丢弃。 */
    DEAD_LETTER_MANAGE("死信重放与丢弃"),
    /** 用户管理。 */
    USER_MANAGE("用户管理");

    private final String label;

    Permission(String label) {
        this.label = label;
    }

    /**
     * @return 对外编码（与枚举名一致，便于落审计与前端展示）
     */
    public String code() {
        return name();
    }

    /**
     * @return 中文说明
     */
    public String getLabel() {
        return label;
    }
}
