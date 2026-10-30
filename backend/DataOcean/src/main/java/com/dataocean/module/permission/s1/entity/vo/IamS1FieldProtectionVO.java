package com.dataocean.module.permission.s1.entity.vo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/** 字段保护的实际判定。 */
@Getter
public class IamS1FieldProtectionVO {
    private final Long columnMetaId;
    private final String tableName;
    private final String columnName;
    private final String protectionLevel;
    private final String maskPolicy;
    private final String reason;

    @JsonCreator
    public IamS1FieldProtectionVO(@JsonProperty("columnMetaId") Long columnMetaId,
                                  @JsonProperty("tableName") String tableName,
                                  @JsonProperty("columnName") String columnName,
                                  @JsonProperty("protectionLevel") String protectionLevel,
                                  @JsonProperty("maskPolicy") String maskPolicy,
                                  @JsonProperty("reason") String reason) {
        this.columnMetaId = columnMetaId;
        this.tableName = tableName;
        this.columnName = columnName;
        this.protectionLevel = protectionLevel;
        this.maskPolicy = maskPolicy;
        this.reason = reason;
    }
}
