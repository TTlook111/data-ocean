package com.dataocean.module.fieldsemantic.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class FieldSemanticDraftDTO {
    @NotNull
    private Long snapshotId;
    @NotNull
    private Integer expectedVersion;
    @NotBlank
    private String businessName;
    @NotBlank
    private String meaning;
    private String unit;
    private String currency;
    private String enumSemantics;
    private String metricDefinition;
    private String applicability;
    @NotBlank
    private String evidence;
    private List<String> unresolvedItems;
    private List<Long> glossaryReviewIds;
}
