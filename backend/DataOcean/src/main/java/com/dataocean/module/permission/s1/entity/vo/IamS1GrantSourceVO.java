package com.dataocean.module.permission.s1.entity.vo;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/** 单份合格 ALLOW grant 的安全来源摘要，保留字段与条件的关联。 */
@Getter
public class IamS1GrantSourceVO {
    private final Long grantId;
    private final String subjectType;
    private final Long subjectId;
    private final String sourceSummary;
    private final String departmentScope;
    private final String grantSource;
    private final Long sourceReferenceId;
    private final LocalDateTime validFrom;
    private final LocalDateTime validUntil;
    private final List<String> explicitColumns;
    private final IamS1RowConditionVO rowCondition;

    @JsonCreator
    public IamS1GrantSourceVO(
            @JsonProperty("grantId") Long grantId,
            @JsonProperty("subjectType") String subjectType,
            @JsonProperty("subjectId") Long subjectId,
            @JsonProperty("sourceSummary") String sourceSummary,
            @JsonProperty("departmentScope") String departmentScope,
            @JsonProperty("grantSource") String grantSource,
            @JsonProperty("sourceReferenceId") Long sourceReferenceId,
            @JsonProperty("validFrom") LocalDateTime validFrom,
            @JsonProperty("validUntil") LocalDateTime validUntil,
            @JsonProperty("explicitColumns") List<String> explicitColumns,
            @JsonProperty("rowCondition") IamS1RowConditionVO rowCondition) {
        this.grantId = grantId;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.sourceSummary = sourceSummary;
        this.departmentScope = departmentScope;
        this.grantSource = grantSource;
        this.sourceReferenceId = sourceReferenceId;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.explicitColumns = explicitColumns == null ? List.of() : List.copyOf(explicitColumns);
        this.rowCondition = rowCondition;
    }
}
