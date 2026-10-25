package com.dataocean.module.fieldsemantic.entity.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

/** 人工使用原验收标准核对修复，不把版本变化当成问题已解决。 */
@Data
public class GovernanceFeedbackVerificationDTO {
    @NotNull @Min(1) private Integer expectedVersion;
    @NotNull @Positive private Long targetRevisionId;
    @NotBlank @Size(max=4000) private String actualResult;
    @NotBlank @Size(max=2000) private String evidenceReference;
    @AssertTrue private boolean confirmed;
}
