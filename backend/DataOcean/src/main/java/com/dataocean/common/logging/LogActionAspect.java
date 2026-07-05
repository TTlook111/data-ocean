package com.dataocean.common.logging;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 日志记录 AOP 切面
 * <p>
 * 拦截带有 {@link LogAction} 注解的方法，自动记录：
 * <ul>
 *   <li>方法开始（含参数）</li>
 *   <li>方法完成（含耗时）</li>
 *   <li>方法失败（含错误信息）</li>
 * </ul>
 * </p>
 *
 * @author DataOcean
 */
@Aspect
@Component
@Slf4j
public class LogActionAspect {

    /**
     * 环绕通知：记录方法执行日志
     *
     * @param joinPoint  连接点
     * @param logAction  日志注解
     * @return 方法返回值
     * @throws Throwable 方法异常
     */
    @Around("@annotation(logAction)")
    public Object around(ProceedingJoinPoint joinPoint, LogAction logAction) throws Throwable {
        String action = logAction.value();
        Object[] args = joinPoint.getArgs();

        // 记录方法开始
        if (logAction.logArgs()) {
            log.info("[{}] 开始 args={}", action, Arrays.toString(args));
        } else {
            log.info("[{}] 开始", action);
        }

        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - start;

            // 记录方法完成
            if (logAction.logResult()) {
                log.info("[{}] 完成 durationMs={} result={}", action, duration, result);
            } else {
                log.info("[{}] 完成 durationMs={}", action, duration);
            }

            return result;
        } catch (Throwable e) {
            long duration = System.currentTimeMillis() - start;
            // 记录方法失败
            log.error("[{}] 失败 durationMs={} error={}", action, duration, e.getMessage());
            throw e;
        }
    }
}
