package com.dataocean.module.metadata.entity.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ManualJoinPathRequest {
    @NotNull
    private Long snapshotId;
    @NotBlank
    private String sourceTable;
    @NotBlank
    private String sourceColumn;
    @NotBlank
    private String targetTable;
    @NotBlank
    private String targetColumn;
    @NotNull
    private Boolean confirmed;

    @AssertTrue(message = "新增 Join Path 前必须明确确认关系条件")
    public boolean isConfirmed() {
        return Boolean.TRUE.equals(confirmed);
    }
}
