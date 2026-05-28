package com.dataocean.common.exception;

import lombok.Getter;

/**
 * 查询已取消异常
 * <p>
 * 当用户主动取消查询或 SSE 连接断开时抛出，
 * 由全局异常处理器捕获并返回 HTTP 499 + 用户友好提示。
 * </p>
 */
@Getter
public class QueryCancelledException extends RuntimeException {

    /** 面向用户的友好提示消息 */
    private final String userMessage;

    /**
     * 构造查询取消异常
     *
     * @param userMessage 面向用户的友好提示
     */
    public QueryCancelledException(String userMessage) {
        super(userMessage);
        this.userMessage = userMessage;
    }
}
