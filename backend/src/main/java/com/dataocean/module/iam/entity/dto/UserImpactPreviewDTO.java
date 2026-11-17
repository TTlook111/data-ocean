package com.dataocean.module.iam.entity.dto;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
@JsonTypeInfo(use=JsonTypeInfo.Id.NAME,include=JsonTypeInfo.As.EXISTING_PROPERTY,property="action",visible=true)
@JsonSubTypes({
    @JsonSubTypes.Type(value=UserUpdatePreviewDTO.class,name="UPDATE"),
    @JsonSubTypes.Type(value=UserTransferPreviewDTO.class,name="TRANSFER"),
    @JsonSubTypes.Type(value=UserEnablePreviewDTO.class,name="ENABLE"),
    @JsonSubTypes.Type(value=UserDisablePreviewDTO.class,name="DISABLE"),
    @JsonSubTypes.Type(value=UserArchivePreviewDTO.class,name="ARCHIVE"),
    @JsonSubTypes.Type(value=UserResetPasswordPreviewDTO.class,name="RESET_PASSWORD"),
    @JsonSubTypes.Type(value=UserProtectedRolesPreviewDTO.class,name="PROTECTED_ROLES")
})
public sealed interface UserImpactPreviewDTO permits UserUpdatePreviewDTO, UserTransferPreviewDTO, UserEnablePreviewDTO, UserDisablePreviewDTO, UserArchivePreviewDTO, UserResetPasswordPreviewDTO, UserProtectedRolesPreviewDTO {
    String getAction();
    Integer getExpectedVersion();
}
