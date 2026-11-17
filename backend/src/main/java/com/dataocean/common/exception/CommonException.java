package com.dataocean.common.exception;

import lombok.Getter;

/** 公共异常保留业务码、HTTP 状态和原始原因，不在响应中暴露内部异常。 */
@Getter
public class CommonException extends RuntimeException {
    private final int code;
    private final int status;

    public CommonException(String message) { this(500001, 500, message, null); }
    public CommonException(String message, Throwable cause) { this(500001, 500, message, cause); }
    public CommonException(int code, String message) { this(code, 500, message, null); }
    public CommonException(int code, String message, Throwable cause) { this(code, 500, message, cause); }
    public CommonException(int code, int status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }
}
