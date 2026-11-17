package com.dataocean.module.iam.entity.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 人员操作共用的影响核对输入，按动作绑定明确的非秘密提案，具体权限仍由业务服务核验。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class UserImpactPreviewDTO {

    /**
     * 本次人员业务动作编码，同时决定提案允许的具体结构。
     */
    @NotBlank
    private String action;

    /**
     * 提交者核对时的聚合版本，服务端据此检查并发变化。
     */
    @NotNull
    @Min(1)
    private Integer expectedVersion;

    /**
     * 按同级 action 绑定的完整提案，类型映射只作用于此字段，不进入提案摘要。
     */
    @NotNull
    @Valid
    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.EXTERNAL_PROPERTY,
            property = "action",
            visible = true)
    @JsonSubTypes({
        @JsonSubTypes.Type(value = UserProfileDTO.class, name = "UPDATE"),
        @JsonSubTypes.Type(value = UserTransferProposalDTO.class, name = "TRANSFER"),
        @JsonSubTypes.Type(value = UserEnableProposalDTO.class, name = "ENABLE"),
        @JsonSubTypes.Type(value = ActionReasonDTO.class, names = {"DISABLE", "ARCHIVE"}),
        @JsonSubTypes.Type(value = UserResetPasswordProposalDTO.class, name = "RESET_PASSWORD"),
        @JsonSubTypes.Type(value = UserProtectedRolesProposalDTO.class, name = "PROTECTED_ROLES")
    })
    private UserProposalDTO proposal;
}
