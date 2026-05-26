package com.dataocean.module.system.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 管理端操作日志注解
 * <p>
 * 标记在 Controller 类上，表示该 Controller 的所有方法调用需要记录操作日志。
 * 新增管理端 Controller 时只需加上此注解即可自动记录，无需修改 AOP pointcut。
 * </p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminAuditLog {
}
