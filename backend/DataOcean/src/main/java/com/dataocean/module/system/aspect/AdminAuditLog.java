package com.dataocean.module.system.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 管理端操作日志注解
 * <p>
 * 标记在 Controller 类上，表示该 Controller 的方法调用需要记录操作日志。
 * 新增管理端 Controller 时只需加上此注解即可自动记录，无需修改 AOP pointcut。
 * </p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminAuditLog {

    /**
     * 是否记录只读操作（GET → QUERY）。
     * <p>
     * 读密集型 Controller（如元数据目录搜索、采集任务轮询）应设为 false，
     * 避免搜索/列表/轮询类 GET 请求刷屏操作日志，仅记录写操作。
     * </p>
     */
    boolean logReads() default true;
}
