package com.dbsync.api.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要自动落审计的端点（T42）。
 *
 * <p>与 {@code @RequiresPermission} 的分工：权限注解决定"能不能做"，
 * 本注解决定"做了什么要留痕"。两者独立，因为只读端点通常只声明权限不落审计
 * （审计表写入量大，架构 §12.3 只要求"写动作"留痕）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /**
     * @return 动作编码（落 {@code sys_audit_log.action}），如 {@code TASK_CREATE}
     */
    String action();

    /**
     * @return 目标类型（落 {@code target_type}），如 {@code TASK} / {@code CUTOVER_PLAN}
     */
    String targetType() default "";
}
