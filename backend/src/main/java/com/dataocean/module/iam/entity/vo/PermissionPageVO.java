package com.dataocean.module.iam.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 系统功能的明确对外视图，只保留调用者可披露字段。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class PermissionPageVO {

    /**
     * 当前功能目录分组对应的页面权代码。
     */
    private String pageCode;

    /**
     * 系统功能目录所属模块编码。
     */
    private String moduleCode;

    /**
     * 当前业务对象的显示名称，身份仍使用稳定标识。
     */
    private String name;

    /**
     * 该页面及其显式所属的功能操作目录。
     */
    private List<PermissionVO> operations;
}
