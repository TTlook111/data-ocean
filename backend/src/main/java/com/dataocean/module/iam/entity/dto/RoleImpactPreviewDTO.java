package com.dataocean.module.iam.entity.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 角色与授予的严格影响核对输入，预览本身不写入业务事实。
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "action",
        visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = RoleUpdatePreviewDTO.class, name = "UPDATE"),
    @JsonSubTypes.Type(value = RoleDisablePreviewDTO.class, name = "DISABLE"),
    @JsonSubTypes.Type(value = RoleEnablePreviewDTO.class, name = "ENABLE"),
    @JsonSubTypes.Type(value = RoleArchivePreviewDTO.class, name = "ARCHIVE"),
    @JsonSubTypes.Type(value = RoleGrantLimitPreviewDTO.class, name = "GRANT_LIMIT"),
    @JsonSubTypes.Type(value = RoleProtectedPermissionsPreviewDTO.class, name = "PROTECTED_PERMISSIONS")
})
public sealed interface RoleImpactPreviewDTO
        permits RoleUpdatePreviewDTO,
                RoleDisablePreviewDTO,
                RoleEnablePreviewDTO,
                RoleArchivePreviewDTO,
                RoleGrantLimitPreviewDTO,
                RoleProtectedPermissionsPreviewDTO {

    /**
     * 取得严格提案类型所绑定的业务动作编码。
     */
    String getAction();

    /**
     * 取得本次影响核对所依据的聚合修订。
     */
    Integer getExpectedVersion();
}
