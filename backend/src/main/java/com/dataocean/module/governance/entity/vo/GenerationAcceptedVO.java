package com.dataocean.module.governance.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 实际提交并等待派发的任务定位。
 */
@Data
@AllArgsConstructor
public class GenerationAcceptedVO {

    /**
     * 已持久接收的 UUID。
     */
    private String requestId;

    /**
     * 原业务命令。
     */
    private String commandId;

    /**
     * 当前任务状态。
     */
    private String state;

    /**
     * 状态快照版本。
     */
    private int version;
}
