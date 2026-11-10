package com.dbsync.api.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明端点所需权限（架构 §12.3）。
 *
 * <p>可标在方法或类上；方法级优先（{@code RbacInterceptor} 先查方法再查类）。
 * 未标注的端点视为"登录即可访问"（例如 {@code /api/auth/login} 与只读的大盘接口要显式标
 * {@link Permission#TASK_VIEW}，见各 Controller）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /**
     * @return 所需权限点
     */
    Permission value();
}
