package com.dataocean.module.permission.s1.entity.vo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/** 结构化谓词的安全摘要；不暴露 structured_value_json。 */
@Getter
public class IamS1RowPredicateVO {
    private final Long columnMetaId;
    private final String columnName;
    private final String operatorCode;
    private final String valueType;
    private final String parameterReference;
    private final String bindingReference;

    @JsonCreator
    public IamS1RowPredicateVO(@JsonProperty("columnMetaId") Long columnMetaId,
                               @JsonProperty("columnName") String columnName,
                               @JsonProperty("operatorCode") String operatorCode,
                               @JsonProperty("valueType") String valueType,
                               @JsonProperty("parameterReference") String parameterReference,
                               @JsonProperty("bindingReference") String bindingReference) {
        this.columnMetaId = columnMetaId;
        this.columnName = columnName;
        this.operatorCode = operatorCode;
        this.valueType = valueType;
        this.parameterReference = parameterReference;
        this.bindingReference = bindingReference;
    }
}
