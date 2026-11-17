package com.dataocean.common.exception;

import lombok.Getter;

/**
 * 公共异常保留业务码、HTTP 状态和原始原因，不在响应中暴露内部异常。
 */
@Getter
public class CommonException extends RuntimeException {

    /**
     * 所属业务对象或系统契约的稳定编码，不以名称替代身份。
     */
    private final int code;

    /**
     * 当前健康结果或业务状态代码。
     */
    private final int status;

    /**
     * 建立该类型所需的明确依赖或值约束。
     */
    public CommonException(String message) {
        this(500001, 500, message, null);
    }

    /**
     * 建立该类型所需的明确依赖或值约束。
     */
    public CommonException(String message, Throwable cause) {
        this(500001, 500, message, cause);
    }

    /**
     * 建立该类型所需的明确依赖或值约束。
     */
    public CommonException(int code, String message) {
        this(code, 500, message, null);
    }

    /**
     * 建立该类型所需的明确依赖或值约束。
     */
    public CommonException(int code, String message, Throwable cause) {
        this(code, 500, message, cause);
    }

    /**
     * 建立该类型所需的明确依赖或值约束。
     */
    public CommonException(int code, int status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }
}
