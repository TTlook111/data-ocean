package com.dataocean.module.governance.entity.vo;

/**
 * 实际提交并等待派发的任务定位。
 *
 * @param requestId 已持久接收的 UUID
 * @param commandId 原业务命令
 * @param state 当前任务状态
 * @param version 状态快照版本
 */
public record GenerationAcceptedVO(
        String requestId,
        String commandId,
        String state,
        int version) {}
