package com.dataocean.common.exception;

import com.dataocean.common.ErrorVO;
import com.dataocean.common.result.Result;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import lombok.extern.slf4j.Slf4j;

/**
 * 将接口校验、业务冲突和依赖故障转换为统一错误及追踪编号。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 只读资源上的编辑或删除请求准确返回 405，不误报系统故障。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Result<ErrorVO>> unsupportedMethod(HttpRequestMethodNotSupportedException error) {
        return ResponseEntity.status(405).body(new Result<>(ErrorCode.VALIDATION_FAILED.getCode(), "当前资源不支持此操作", null));
    }

    /**
     * 未提供的接口保留资源不存在语义。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Result<ErrorVO>> missingResource(NoResourceFoundException error) {
        return ResponseEntity.status(404).body(new Result<>(ErrorCode.RESOURCE_NOT_FOUND.getCode(), "资源不存在", null));
    }

    /**
     * 缺少防重请求头或查询类型不匹配属于输入错误。
     */
    @ExceptionHandler({
        MissingRequestHeaderException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestPartException.class
    })
    ResponseEntity<Result<ErrorVO>> invalidBinding(Exception error) {
        return ResponseEntity.badRequest()
                .body(new Result<>(ErrorCode.VALIDATION_FAILED.getCode(), "请求参数或必要请求头不完整", null));
    }

    /**
     * 在文件读取前提供可操作的容量提示。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Result<ErrorVO>> uploadTooLarge() {
        return ResponseEntity.status(413)
                .body(new Result<>(ErrorCode.VALIDATION_FAILED.getCode(), "上传文件或请求总大小超出限制", null));
    }

    /**
     * 将明确业务异常转换为约定的 HTTP 状态与业务码。
     */
    @ExceptionHandler(CommonException.class)
    ResponseEntity<Result<ErrorVO>> business(CommonException error) {
        return ResponseEntity.status(error.getStatus()).body(new Result<>(error.getCode(), error.getMessage(), null));
    }

    /**
     * 仅返回字段名和安全提示，禁止回显校验拒绝的密码值。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Result<ErrorVO>> validation(MethodArgumentNotValidException error) {
        // 只返回字段名与校验提示，绝不把 rejectedValue 中的密码带回响应或日志。
        var fields = error.getBindingResult().getFieldErrors().stream()
                .map(field ->
                        new ErrorVO.FieldErrorVO(field.getField(), "VALIDATION_FAILED", field.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(new Result<>(ErrorCode.VALIDATION_FAILED.getCode(), "请检查填写内容", new ErrorVO(fields, null, null)));
    }

    /**
     * 拒绝未知字段、无效类型及错误枚举，不把秘密原值回显。
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<Result<ErrorVO>> unreadable(Exception error) {
        return ResponseEntity.badRequest()
                .body(new Result<>(ErrorCode.VALIDATION_FAILED.getCode(), "请求字段、格式或枚举不正确", null));
    }

    /**
     * 将数据库唯一约束冲突转换为所属业务的明确防重错误。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Result<ErrorVO>> duplicate(DuplicateKeyException error) {
        String text = error.getMostSpecificCause().getMessage();
        String code = text.contains("uk_user_username")
                ? "IAM_LOGIN_NAME_EXISTS"
                : text.contains("uk_role_name_key")
                        ? "IAM_ROLE_NAME_EXISTS"
                        : text.contains("uk_department_code") ? "IAM_DEPARTMENT_CODE_EXISTS" : "STATE_CONFLICT";
        return ResponseEntity.status(409)
                .body(new Result<>(ErrorCode.valueOf(code).getCode(), "该名称、编号或当前关系已存在，请重新核对", null));
    }

    /**
     * 将平台持久化依赖故障准确映射为暂不可用，不伪装空结果。
     */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Result<ErrorVO>> storage(DataAccessException error, HttpServletResponse response) {
        // SQL 异常可能带绑定参数，常见依赖故障只记录请求关联与类型。
        log.warn(
                "平台存储处理失败，traceId={}, errorType={}",
                response.getHeader("X-Trace-Id"),
                error.getClass().getSimpleName());
        return ResponseEntity.status(503)
                .body(new Result<>(ErrorCode.DEPENDENCY_UNAVAILABLE.getCode(), "必要存储当前不可用，请稍后重试", null));
    }

    /**
     * 统一处理未预期故障，外部只返回受控信息。
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Result<ErrorVO>> unknown(Exception error, HttpServletResponse response) {
        // 未预期故障在最终处理边界记录一次，外部仍只收到受控提示。
        log.error("请求处理异常，traceId={}", response.getHeader("X-Trace-Id"), error);
        return ResponseEntity.internalServerError()
                .body(new Result<>(ErrorCode.INTERNAL_ERROR.getCode(), "操作未完成，请通过请求编号联系管理员", null));
    }
}
