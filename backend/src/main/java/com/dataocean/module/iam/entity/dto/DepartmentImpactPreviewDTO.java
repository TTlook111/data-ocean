package com.dataocean.module.iam.entity.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 部门与成员的严格影响核对输入，预览本身不写入业务事实。
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "action",
        visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = DepartmentUpdatePreviewDTO.class, name = "UPDATE"),
    @JsonSubTypes.Type(value = DepartmentAddSecondaryPreviewDTO.class, name = "ADD_SECONDARY"),
    @JsonSubTypes.Type(value = DepartmentEndMembershipPreviewDTO.class, name = "END_MEMBERSHIP"),
    @JsonSubTypes.Type(value = DepartmentMoveMembersPreviewDTO.class, name = "MOVE_MEMBERS"),
    @JsonSubTypes.Type(value = DepartmentMoveChildrenPreviewDTO.class, name = "MOVE_CHILDREN"),
    @JsonSubTypes.Type(value = DepartmentDisablePreviewDTO.class, name = "DISABLE"),
    @JsonSubTypes.Type(value = DepartmentEnablePreviewDTO.class, name = "ENABLE"),
    @JsonSubTypes.Type(value = DepartmentArchivePreviewDTO.class, name = "ARCHIVE")
})
public sealed interface DepartmentImpactPreviewDTO
        permits DepartmentUpdatePreviewDTO,
                DepartmentAddSecondaryPreviewDTO,
                DepartmentEndMembershipPreviewDTO,
                DepartmentMoveMembersPreviewDTO,
                DepartmentMoveChildrenPreviewDTO,
                DepartmentDisablePreviewDTO,
                DepartmentEnablePreviewDTO,
                DepartmentArchivePreviewDTO {

    /**
     * 取得严格提案类型所绑定的业务动作编码。
     */
    String getAction();

    /**
     * 取得本次影响核对所依据的聚合修订。
     */
    Integer getExpectedVersion();
}
