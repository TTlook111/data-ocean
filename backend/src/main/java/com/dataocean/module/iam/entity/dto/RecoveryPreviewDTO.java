package com.dataocean.module.iam.entity.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 历史角色恢复的预览输入，正式提交复用同一份历史选择、原因和并发依据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecoveryPreviewDTO {

    /**
     * 提交者核对时的聚合版本，服务端以当前版本检查并发变化。
     */
    @NotNull
    @Min(1)
    private Integer expectedVersion;

    /**
     * 与目标人员原有管理分配真实关联的历史审计标识。
     */
    @NotBlank
    @Pattern(regexp = "[1-9][0-9]{0,18}")
    private String sourceAuditId;

    /**
     * 本次管理动作的必填原因，不写入秘密凭据。
     */
    @NotBlank
    @CodePointLength(max = 500)
    private String reason;
}
