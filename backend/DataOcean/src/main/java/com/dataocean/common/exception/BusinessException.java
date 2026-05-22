package com.dataocean.common.exception;

import lombok.Getter;

/**
 * 业务异常类
 * <p>
 * 用于在业务逻辑中抛出带有错误码和错误消息的异常，
 * 由全局异常处理器统一捕获并返回给前端。
 * </p>
 */
@Getter
public class BusinessException extends RuntimeException {

    /** HTTP 状态码或自定义业务错误码 */
    private final int code;

    /**
     * 构造业务异常（默认错误码 400）
     *
     * @param message 异常描述信息
     */
    public BusinessException(String message) {
        this(400, message);
    }

    /**
     * 构造业务异常（自定义错误码）
     *
     * @param code    错误码
     * @param message 异常描述信息
     */
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
