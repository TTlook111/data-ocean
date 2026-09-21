package com.dataocean.module.permission.s1.entity.vo;

import java.util.List;

/**
 * 首期角色模板。模板只帮助勾选功能，创建后是普通角色，没有按名字识别的特权，也不附送数据授权。
 *
 * @param capabilitySummary 选模板后直接显示“已获得的能力”
 * @param dataHint          数据如何获得（部门默认或明确授权）
 */
public record IamS1RoleTemplateVO(String code,
                                  String name,
                                  String description,
                                  List<String> functionCodes,
                                  List<String> functionNames,
                                  String capabilitySummary,
                                  String dataHint,
                                  boolean systemAdminOnly) {
}
