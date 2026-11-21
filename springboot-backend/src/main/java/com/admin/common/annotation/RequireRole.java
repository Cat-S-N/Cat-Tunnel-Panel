package com.admin.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限控制注解
 * 用于标记需要管理员权限的方法（role_id = 0）
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    /**
     * 是否允许被分配了特定节点管理权限的子管理员访问。
     * 为 true 时，具体方法内部必须进一步校验目标 nodeId 是否在用户授权节点白名单内。
     */
    boolean allowNodeAdmin() default false;
} 