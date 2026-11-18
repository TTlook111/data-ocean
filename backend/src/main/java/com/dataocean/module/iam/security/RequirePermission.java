package com.dataocean.module.iam.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明进入接口前必须满足的权限条件。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /**
     * 必须全部具备的页面或操作编码。
     */
    String[] value() default {};

    /**
     * 至少具备其中一项的页面或操作编码。
     */
    String[] anyOf() default {};

    /**
     * 使用已绑定的请求参数决定所需权限。
     */
    Class<? extends RequestPermission> policy() default RequestPermission.class;

    /**
     * 声明功能接口、本人会话接口或公开接口的准入方式。
     */
    Access access() default Access.PERMISSION;

    /**
     * 接口允许的三种准入方式。
     */
    enum Access {
        PERMISSION,
        AUTHENTICATED,
        PUBLIC
    }
}
