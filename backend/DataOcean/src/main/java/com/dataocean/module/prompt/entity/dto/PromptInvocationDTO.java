package com.dataocean.module.prompt.entity.dto;
import jakarta.validation.constraints.*;
import lombok.Data;
/** Python 内部调用回执；枚举及场景再由服务层验证。 */
@Data
public class PromptInvocationDTO {
    @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{1,64}") private String invocationId;
    @Size(max = 100) private String taskId;
    @NotBlank @Size(max = 50) private String sceneCode;
    @NotNull @Min(0) private Integer versionNo;
    @NotBlank @Pattern(regexp = "MANAGED|BUILTIN") private String source;
    @NotBlank @Pattern(regexp = "1") private String contractVersion;
    @NotBlank @Pattern(regexp = "[a-f0-9]{64}") private String contentHash;
    @NotNull @Pattern(regexp = "|MISSING|DISABLED|UNAVAILABLE|INVALID_TEMPLATE") private String fallbackReason;
    @NotBlank @Pattern(regexp = "MODEL_RETURNED|MODEL_FAILED") private String outcome;
    @NotNull @Min(0) @Max(86400000) private Long durationMs;
}
