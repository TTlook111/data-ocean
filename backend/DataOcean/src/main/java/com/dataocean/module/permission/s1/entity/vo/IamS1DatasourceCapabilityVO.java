package com.dataocean.module.permission.s1.entity.vo;

import java.util.List;

/**
 * 某数据源上的 S1 后台能力。只列出“功能与负责源在同一角色绑定上”成立的功能码。
 */
public record IamS1DatasourceCapabilityVO(Long datasourceId,
                                          String datasourceName,
                                          List<String> functionCodes,
                                          List<String> functionNames) {
}
