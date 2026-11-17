package com.dataocean.module.iam.entity.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 人员资料的严格影响核对输入，预览本身不写入业务事实。
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "action",
        visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = UserUpdatePreviewDTO.class, name = "UPDATE"),
    @JsonSubTypes.Type(value = UserTransferPreviewDTO.class, name = "TRANSFER"),
    @JsonSubTypes.Type(value = UserEnablePreviewDTO.class, name = "ENABLE"),
    @JsonSubTypes.Type(value = UserDisablePreviewDTO.class, name = "DISABLE"),
    @JsonSubTypes.Type(value = UserArchivePreviewDTO.class, name = "ARCHIVE"),
    @JsonSubTypes.Type(value = UserResetPasswordPreviewDTO.class, name = "RESET_PASSWORD"),
    @JsonSubTypes.Type(value = UserProtectedRolesPreviewDTO.class, name = "PROTECTED_ROLES")
})
public sealed interface UserImpactPreviewDTO
        permits UserUpdatePreviewDTO,
                UserTransferPreviewDTO,
                UserEnablePreviewDTO,
                UserDisablePreviewDTO,
                UserArchivePreviewDTO,
                UserResetPasswordPreviewDTO,
                UserProtectedRolesPreviewDTO {

    /**
     * 取得严格提案类型所绑定的业务动作编码。
     */
    String getAction();

    /**
     * 取得本次影响核对所依据的聚合修订。
     */
    Integer getExpectedVersion();
}
