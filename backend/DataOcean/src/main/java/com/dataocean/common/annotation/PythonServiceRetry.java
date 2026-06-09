package com.dataocean.common.annotation;

import com.dataocean.common.exception.PythonRetryableException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Python 服务调用重试注解。
 * <p>
 * 封装通用的重试配置：最多重试 2 次，间隔 1 秒，
 * 仅对 {@link PythonRetryableException} 进行重试。
 * </p>
 * <p>
 * 使用示例：
 * <pre>
 * {@literal @}Override
 * {@literal @}PythonServiceRetry
 * public Map<String, Object> someMethod() { ... }
 * </pre>
 * </p>
 * <p>
 * 注意：只能用于 Spring 代理可拦截的 public 方法；不要用于 private 方法或同类自调用方法。
 * </p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Retryable(
        retryFor = PythonRetryableException.class,
        maxAttempts = 2,
        backoff = @Backoff(delay = 1000)
)
public @interface PythonServiceRetry {
}
