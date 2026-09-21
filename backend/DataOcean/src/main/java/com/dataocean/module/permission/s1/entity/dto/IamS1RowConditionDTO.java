package com.dataocean.module.permission.s1.entity.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 结构化记录条件谓词。valueJson 只允许类型化标量或数组，禁止 SQL 文本。 */
@Getter
@Setter
@NoArgsConstructor
public class IamS1RowConditionDTO {
    private Long columnMetaId;
    private String columnName;
    private String operatorCode;
    private String valueType;
    private String structuredValueJson;
    private String parameterReference;

    public IamS1RowConditionDTO(Long columnMetaId, String columnName, String operatorCode,
                                String valueType, String structuredValueJson, String parameterReference) {
        this.columnMetaId = columnMetaId;
        this.columnName = columnName;
        this.operatorCode = operatorCode;
        this.valueType = valueType;
        this.structuredValueJson = structuredValueJson;
        this.parameterReference = parameterReference;
    }
}
