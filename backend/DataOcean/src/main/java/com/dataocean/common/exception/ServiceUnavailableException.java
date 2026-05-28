package com.dataocean.common.exception;

import lombok.Getter;

/**
 * 服务不可用异常
 * <p>
 * 当外部依赖服务（如 Python AI 服务）不可用时抛出，
 * 由全局异常处理器捕获并返回 HTTP 503 + 用户友好提示。
 * </p>
 */
@Getter
public class ServiceUnavailableException extends RuntimeException {

    /** 面向用户的友好提示消息 */
    private final String userMessage;

    /**
     * 构造服务不可用异常
     *
     * @param userMessage 面向用户的友好提示
     */
    public ServiceUnavailableException(String userMessage) {
        super(userMessage);
        this.userMessage = userMessage;
    }

    /**
     * 构造服务不可用异常（含原始异常）
     *
     * @param userMessage 面向用户的友好提示
     * @param cause       原始异常
     */
    public ServiceUnavailableException(String userMessage, Throwable cause) {
        super(userMessage, cause);
        this.userMessage = userMessage;
    }
}
