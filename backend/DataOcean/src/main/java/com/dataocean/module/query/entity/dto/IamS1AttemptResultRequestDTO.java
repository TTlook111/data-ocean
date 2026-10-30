package com.dataocean.module.query.entity.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** Unmasked query executor callback. Java validates and protects it before returning any row. */
@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class IamS1AttemptResultRequestDTO {
    @NotBlank
    private String attemptId;
    @NotBlank
    @Pattern(regexp = "[0-9a-f]{64}")
    private String sqlHash;
    private boolean success;
    private String error;
    private String errorType;
    private List<Map<String, Object>> data;
    private List<Map<String, String>> columns;
    private List<String> usedTables;
    private List<String> usedColumns;
    private List<Map<String, Object>> sourceTrace;
    private Integer rowCount;
    private Integer executionTimeMs;
}
