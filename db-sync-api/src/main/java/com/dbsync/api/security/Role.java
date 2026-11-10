package com.dbsync.api.security;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 角色（架构 §12.3 权限矩阵）。
 *
 * <p>矩阵原文（12 个动作 × 3 角色）：
 * <pre>
 * 动作                        VIEWER OPERATOR ADMIN
 * 查看大盘/报告/审计            ✔      ✔       ✔
 * 创建/编辑任务                 ✘      ✔       ✔
 * 启动/暂停/恢复/重跑任务        ✘      ✔       ✔
 * 重放/丢弃死信                 ✘      ✔       ✔
 * 发起比对                      ✘      ✔       ✔
 * REPLAY_TO_TARGET 修复         ✘      ✔       ✔
 * FIX_TO_SOURCE 反向修复        ✘      ✘       ✔
 * 数据源新增/编辑/删除           ✘      ✘       ✔
 * 切流推进/回滚                 ✘      ✘       ✔
 * 跳过 Gate                     ✘      ✘       ✔
 * 用户管理                      ✘      ✘       ✔
 * </pre>
 *
 * <p>映射到 12 个 {@link Permission} 时，"查看"合并为 {@link Permission#TASK_VIEW} 与
 * {@link Permission#CUTOVER_VIEW}（两者都只对 VIEWER 及以上开放），
 * "REPLAY_TO_TARGET" 复用 {@link Permission#COMPARE_RUN}（同为 OPERATOR 权限），
 * 因此仍然严格等价于架构 §12.3 的矩阵，{@code RbacTest} 会逐格断言。
 */
public enum Role {

    /** 只读。 */
    VIEWER(EnumSet.of(Permission.TASK_VIEW, Permission.CUTOVER_VIEW)),

    /** 运维：可执行迁移/比对/死信处理，不可改数据源、不可切流。 */
    OPERATOR(EnumSet.of(Permission.TASK_VIEW, Permission.CUTOVER_VIEW, Permission.TASK_EDIT,
            Permission.TASK_EXEC, Permission.COMPARE_RUN, Permission.DEAD_LETTER_MANAGE)),

    /** 管理员：全部权限。 */
    ADMIN(EnumSet.allOf(Permission.class));

    private final Set<Permission> permissions;

    Role(EnumSet<Permission> permissions) {
        this.permissions = Collections.unmodifiableSet(permissions);
    }

    /**
     * 判定是否拥有权限。
     *
     * @param permission 权限点（null 视为通过，便于"只读端点不声明权限"）
     * @return 有权限返回 true
     */
    public boolean has(Permission permission) {
        return permission == null || permissions.contains(permission);
    }

    /**
     * @return 不可变的权限集合
     */
    public Set<Permission> permissions() {
        return permissions;
    }

    /**
     * 解析角色名（大小写不敏感）。
     *
     * @param name 角色名
     * @return 角色；无法识别时降级为 {@link #VIEWER}（最安全：权限最小）
     */
    public static Role parse(String name) {
        if (name == null || name.isBlank()) {
            return VIEWER;
        }
        String trimmed = name.trim();
        for (Role role : values()) {
            if (role.name().equalsIgnoreCase(trimmed)) {
                return role;
            }
        }
        return VIEWER;
    }

    /**
     * @return 角色编码
     */
    public String code() {
        return name();
    }
}
