package com.dataocean.module.iam.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 系统功能的明确传输结构，字段职责及校验与接口契约一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PermissionOperationSeedDTO {

    /**
     * 该记录的稳定标识，对外长整型以十进制字符串表达。
     */
    private String id;

    /**
     * 当前业务对象的显示名称，身份仍使用稳定标识。
     */
    private String name;

    /**
     * 本次选项查询或系统功能的明确业务用途。
     */
    private String purpose;

    /**
     * 该功能是否为受保护管理能力。
     */
    private boolean protectedAbility;
}
