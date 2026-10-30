package com.dataocean.module.permission.s1.entity.vo;

/** 已发布快照中可授权的字段选项。字段中文名取自已治理元数据，缺少业务名称时显示物理名。 */
public record IamS1ColumnOptionVO(Long columnMetaId,
                                  String columnName,
                                  String columnComment,
                                  String dataType,
                                  String governanceStatus,
                                  String protectionLevel,
                                  boolean selectable) {
}
