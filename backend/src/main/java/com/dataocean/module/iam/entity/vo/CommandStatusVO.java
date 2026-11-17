package com.dataocean.module.iam.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 公共契约的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class CommandStatusVO {

    /**
     * 当前主体本次操作的唯一命令编号，用于防重与提交核实。
     */
    private String commandId;

    /**
     * 原业务动作代码，与当前用户和命令编号共同定位命令。
     */
    private String operationCode;

    /**
     * 原命令的接收、提交或拒绝状态。
     */
    private String state;

    /**
     * 命令最终结果的业务码。
     */
    private String resultCode;

    /**
     * 本次命令已提交的安全目标引用。
     */
    private EntityVersionRefVO target;
}
