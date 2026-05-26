package com.dataocean.module.system.aspect;

import com.dataocean.common.security.UserContext;
import com.dataocean.module.system.entity.SysOperationLog;
import com.dataocean.module.system.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

/**
 * 管理端操作日志 AOP 切面
 * <p>
 * 自动记录所有 /api/admin/* 接口的调用信息。
 * </p>
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class OperationLogAspect {

    private final OperationLogService operationLogService;

    /**
     * 环绕通知：仅拦截管理端 Controller（路径含 /api/admin 的 Controller）
     */
    @Around("("
            + "execution(* com.dataocean.module.datasource.controller.DatasourceAdminController.*(..)) || "
            + "execution(* com.dataocean.module.user.controller.*.*(..)) || "
            + "execution(* com.dataocean.module.fieldtag.controller.FieldTagController.*(..)) || "
            + "execution(* com.dataocean.module.fieldtag.controller.FieldAdminController.*(..)) || "
            + "execution(* com.dataocean.module.fieldtag.controller.FeedbackReviewController.*(..)) || "
            + "execution(* com.dataocean.module.audit.controller.AuditLogController.*(..)) || "
            + "execution(* com.dataocean.module.audit.controller.AlertController.*(..)) || "
            + "execution(* com.dataocean.module.system.controller.OperationLogController.*(..))"
            + ")")
    public Object logOperation(ProceedingJoinPoint joinPoint) throws Throwable {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return joinPoint.proceed();
        }
        HttpServletRequest request = attrs.getRequest();
        String path = request.getRequestURI();

        long startTime = System.currentTimeMillis();
        SysOperationLog opLog = new SysOperationLog();
        opLog.setRequestMethod(request.getMethod());
        opLog.setRequestPath(path);
        opLog.setIpAddress(getClientIp(request));
        opLog.setCreatedAt(LocalDateTime.now());

        try {
            Long userId = UserContext.currentUserId();
            opLog.setOperatorId(userId);
            opLog.setOperatorName(UserContext.currentUsername());
        } catch (Exception ignored) {
            // 未登录场景忽略
        }

        // 推断操作类型
        opLog.setOperationType(inferOperationType(request.getMethod()));
        // 推断目标资源
        opLog.setTargetResource(inferResource(path));

        Object result;
        try {
            result = joinPoint.proceed();
            opLog.setIsSuccess(true);
        } catch (Throwable e) {
            opLog.setIsSuccess(false);
            opLog.setErrorMessage(e.getMessage() != null ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 500)) : "未知错误");
            throw e;
        } finally {
            opLog.setExecutionMs((int) (System.currentTimeMillis() - startTime));
            operationLogService.record(opLog);
        }
        return result;
    }

    /**
     * 根据 HTTP 方法推断操作类型
     */
    private String inferOperationType(String method) {
        return switch (method.toUpperCase()) {
            case "POST" -> "CREATE";
            case "PUT", "PATCH" -> "UPDATE";
            case "DELETE" -> "DELETE";
            default -> "QUERY";
        };
    }

    /**
     * 根据请求路径推断目标资源
     */
    private String inferResource(String path) {
        // /api/admin/datasources/1 → datasources
        String[] parts = path.replace("/api/admin/", "").split("/");
        return parts.length > 0 ? parts[0] : "unknown";
    }

    /**
     * 获取客户端真实 IP 地址
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        }
        return ip.contains(",") ? ip.split(",")[0].trim() : ip;
    }
}
