package com.dataocean.common.logging;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 日志记录注解
 * <p>
 * 标记在方法上，由 AOP 切面自动记录方法调用日志，
 * 包括方法名、参数、执行时间、返回值等信息。
 * </p>
 *
 * @author DataOcean
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LogAction {

    /**
     * 操作描述（用于日志标识）
     */
    String value();

    /**
     * 是否记录方法参数
     */
    boolean logArgs() default false;

    /**
     * 是否记录返回值
     */
    boolean logResult() default false;
}
