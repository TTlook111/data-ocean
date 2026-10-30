package com.dataocean.module.permission.s1.entity.vo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.List;

/** 一张表的明确字段、授权来源和保护判定。 */
@Getter
public class IamS1TablePermissionVO {
    private final boolean allowed;
    private final String reasonCode;
    private final String tableName;
    private final List<String> allowedColumns;
    private final List<IamS1GrantSourceVO> grantSources;
    private final List<IamS1FieldProtectionVO> fieldProtections;
    private final List<String> reasons;

    @JsonCreator
    public IamS1TablePermissionVO(
            @JsonProperty("allowed") boolean allowed,
            @JsonProperty("reasonCode") String reasonCode,
            @JsonProperty("tableName") String tableName,
            @JsonProperty("allowedColumns") List<String> allowedColumns,
            @JsonProperty("grantSources") List<IamS1GrantSourceVO> grantSources,
            @JsonProperty("fieldProtections") List<IamS1FieldProtectionVO> fieldProtections,
            @JsonProperty("reasons") List<String> reasons) {
        this.allowed = allowed;
        this.reasonCode = reasonCode;
        this.tableName = tableName;
        this.allowedColumns = allowedColumns == null ? List.of() : List.copyOf(allowedColumns);
        this.grantSources = grantSources == null ? List.of() : List.copyOf(grantSources);
        this.fieldProtections = fieldProtections == null ? List.of() : List.copyOf(fieldProtections);
        this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
