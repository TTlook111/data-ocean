package com.dataocean.common.exception;

import com.dataocean.common.result.Result;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 * <p>
 * 统一拦截 Controller 层抛出的各类异常，转换为标准的 Result 响应格式返回给前端，
 * 避免将原始异常堆栈暴露给客户端。
 * </p>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 处理业务异常
     * <p>根据异常中的错误码动态设置 HTTP 状态码</p>
     *
     * @param exception 业务异常
     * @return 包含错误信息的响应实体
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException exception) {
        // 尝试将业务错误码映射为 HTTP 状态码
        HttpStatus status = HttpStatus.resolve(exception.getCode());
        // 如果映射失败或不是客户端/服务端错误，则默认使用 400
        if (status == null || status.is1xxInformational() || status.is2xxSuccessful() || status.is3xxRedirection()) {
            status = HttpStatus.BAD_REQUEST;
        }
        log.warn("业务异常已处理 code={} message={}", exception.getCode(), exception.getMessage());
        return ResponseEntity.status(status).body(Result.error(exception.getCode(), exception.getMessage()));
    }

    /**
     * 处理请求参数校验异常（@Valid 注解触发）
     *
     * @param exception 参数校验异常
     * @return 包含校验错误信息的结果
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidationException(MethodArgumentNotValidException exception) {
        // 提取第一个字段校验错误信息
        FieldError fieldError = exception.getBindingResult().getFieldError();
        String message = fieldError == null ? "请求参数不合法" : fieldError.getDefaultMessage();
        log.warn("请求参数校验失败 message={}", message);
        return Result.error(400, message);
    }

    /**
     * 处理约束违反异常（@Validated 注解在方法参数上触发）
     *
     * @param exception 约束违反异常
     * @return 包含约束错误信息的结果
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleConstraintViolation(ConstraintViolationException exception) {
        log.warn("约束校验失败 message={}", exception.getMessage());
        return Result.error(400, exception.getMessage());
    }

    /**
     * 处理认证异常（未登录或 Token 无效）
     *
     * @param exception 认证异常
     * @return 401 未认证结果
     */
    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Result<Void> handleAuthenticationException(AuthenticationException exception) {
        log.warn("认证异常已处理 type={}", exception.getClass().getSimpleName());
        return Result.error(401, "认证失败");
    }

    /**
     * 处理访问拒绝异常（已认证但无权限）
     *
     * @param exception 访问拒绝异常
     * @return 403 无权限结果
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleAccessDeniedException(AccessDeniedException exception) {
        log.warn("访问拒绝异常已处理 message={}", exception.getMessage());
        return Result.error(403, "无访问权限");
    }

    /**
     * 处理所有未被捕获的异常（兜底处理）
     *
     * @param exception 未知异常
     * @return 500 服务器内部错误结果
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception exception) {
        log.error("未处理异常", exception);
        return Result.error(500, "服务器内部错误");
    }
}
