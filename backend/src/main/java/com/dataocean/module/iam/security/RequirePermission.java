package com.dataocean.module.iam.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明进入方法前所需的固定功能权限。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /**
     * 权限目录中的页面或操作编码。
     */
    String value();
}
