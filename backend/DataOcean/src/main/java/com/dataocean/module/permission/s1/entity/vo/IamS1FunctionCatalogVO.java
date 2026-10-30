package com.dataocean.module.permission.s1.entity.vo;

import java.util.List;

/**
 * 固定功能目录条目。仅用于只读中文说明与角色配置，不允许通过接口新增或修改功能码。
 *
 * @param code            功能码（开发/排查用，不是管理员的主要配置界面）
 * @param name            中文名称
 * @param description     勾选后可以做什么
 * @param domain          一级业务域
 * @param workspace       二级工作区
 * @param dependencies     依赖的功能码（维护包含查看等）
 * @param systemAdminOnly 是否只能由系统管理员配置或分配
 * @param active          功能是否启用
 */
public record IamS1FunctionCatalogVO(String code,
                                     String name,
                                     String description,
                                     String domain,
                                     String workspace,
                                     List<String> dependencies,
                                     boolean systemAdminOnly,
                                     boolean active) {
}
