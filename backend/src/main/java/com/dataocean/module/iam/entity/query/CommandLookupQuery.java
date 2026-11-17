package com.dataocean.module.iam.entity.query;

import lombok.Data;

/**
 * 公共契约的明确查询条件，分页与排序遵循统一白名单约束。
 */
@Data
public class CommandLookupQuery {

    /**
     * 原业务动作代码，与当前用户和命令编号共同定位命令。
     */
    private String operationCode = null;
}
