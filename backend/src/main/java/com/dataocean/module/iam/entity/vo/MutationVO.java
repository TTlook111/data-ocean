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
public final class MutationVO {

    /**
     * 当前主体本次操作的唯一命令编号，用于防重与提交核实。
     */
    private String commandId;

    /**
     * 当前目标所属的明确业务类型。
     */
    private String targetType;

    /**
     * 当前业务目标的稳定标识。
     */
    private String targetId;

    /**
     * 该业务聚合的当前修订，写操作使用它检查并发变化。
     */
    private int version;
}
