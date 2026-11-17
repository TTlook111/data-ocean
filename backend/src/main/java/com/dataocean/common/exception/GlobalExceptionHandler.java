package com.dataocean.common.exception;

import com.dataocean.common.ErrorVO;
import com.dataocean.common.result.Result;

import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.exception.BizIllegalException;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(com.dataocean.common.exception.CommonException.class)
    ResponseEntity<Result<ErrorVO>> business(com.dataocean.common.exception.CommonException error) {
        return ResponseEntity.status(error.getStatus()).body(new Result<>(error.getCode(), error.getMessage(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Result<ErrorVO>> validation(MethodArgumentNotValidException error) {
        // 只返回字段名与校验提示，绝不把 rejectedValue 中的密码带回响应或日志。
        var fields = error.getBindingResult().getFieldErrors().stream()
                .map(field -> new ErrorVO.FieldErrorVO(field.getField(), "VALIDATION_FAILED", field.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(new Result<>(ErrorCode.VALIDATION_FAILED.getCode(), "请检查填写内容", new ErrorVO(fields, null, null)));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class})
    ResponseEntity<Result<ErrorVO>> unreadable(Exception error) {
        return ResponseEntity.badRequest().body(new Result<>(ErrorCode.VALIDATION_FAILED.getCode(), "请求字段、格式或枚举不正确", null));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<Result<ErrorVO>> duplicate(DuplicateKeyException error) {
        String text = error.getMostSpecificCause().getMessage();
        String code = text.contains("uk_user_username") ? "IAM_LOGIN_NAME_EXISTS"
                : text.contains("uk_role_name_key") ? "IAM_ROLE_NAME_EXISTS"
                : text.contains("uk_department_code") ? "IAM_DEPARTMENT_CODE_EXISTS" : "STATE_CONFLICT";
        return ResponseEntity.status(409).body(new Result<>(ErrorCode.valueOf(code).getCode(), "该名称、编号或当前关系已存在，请重新核对", null));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Result<ErrorVO>> storage(DataAccessException error) {
        return ResponseEntity.status(503).body(new Result<>(ErrorCode.DEPENDENCY_UNAVAILABLE.getCode(), "必要存储当前不可用，请稍后重试", null));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Result<ErrorVO>> unknown(Exception error) {
        return ResponseEntity.internalServerError().body(new Result<>(ErrorCode.INTERNAL_ERROR.getCode(), "操作未完成，请通过请求编号联系管理员", null));
    }
}
