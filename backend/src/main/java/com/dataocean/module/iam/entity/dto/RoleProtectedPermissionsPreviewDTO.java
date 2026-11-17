package com.dataocean.module.iam.entity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 角色与授予的严格影响核对输入，预览本身不写入业务事实。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class RoleProtectedPermissionsPreviewDTO implements RoleImpactPreviewDTO {

    /**
     * 本次业务动作的契约编码，用于选择严格对应的提案类型。
     */
    @NotBlank
    private String action;

    /**
     * 提交者核对时的聚合版本，服务端以当前版本检查并发变化。
     */
    @NotNull
    @Min(1)
    private Integer expectedVersion;

    /**
     * 与具体动作严格对应的完整结构化提案。
     */
    @NotNull
    @Valid
    private RoleProtectedPermissionsProposalDTO proposal;
}
