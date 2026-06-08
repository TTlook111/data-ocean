package com.dataocean.common.exception;

/**
 * 调用 Python 服务时可安全重试的异常。
 */
public class PythonRetryableException extends RuntimeException {

    public PythonRetryableException(String message) {
        super(message);
    }

    public PythonRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
