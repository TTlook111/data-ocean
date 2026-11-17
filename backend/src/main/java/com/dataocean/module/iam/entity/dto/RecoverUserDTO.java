package com.dataocean.module.iam.entity.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.hibernate.validator.constraints.CodePointLength;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 人员资料的明确传输结构，字段职责及校验与接口契约一致。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class RecoverUserDTO {

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

    /**
     * 绑定操作者、完整提案、当前版本和到期时间的签名凭据。
     */
    @NotBlank
    @ToString.Exclude
    private String reviewToken;
}
