package com.dataocean.module.knowledge.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Request issued only after the user confirms an explicit RAG build. */
@Data
public class RagIndexBuildRequest {
    @NotNull
    private Long datasourceId;
    @NotNull
    private Long snapshotId;
    @NotNull
    private Boolean confirmed;

    @AssertTrue(message = "必须明确确认构建本次 RAG")
    public boolean isConfirmed() {
        return Boolean.TRUE.equals(confirmed);
    }
}
