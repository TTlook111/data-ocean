package com.dataocean.module.iam.entity.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 系统功能的明确传输结构，字段职责及校验与接口契约一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PermissionPageSeedDTO {

    /**
     * 该记录的稳定标识，对外长整型以十进制字符串表达。
     */
    private String id;

    /**
     * 功能页面所属的业务展示分组。
     */
    private String group;

    /**
     * 当前业务对象的显示名称，身份仍使用稳定标识。
     */
    private String name;

    /**
     * 该页面及其显式所属的功能操作目录。
     */
    private List<PermissionOperationSeedDTO> operations;
}
