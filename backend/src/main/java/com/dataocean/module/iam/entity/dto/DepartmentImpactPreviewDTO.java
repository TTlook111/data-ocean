package com.dataocean.module.iam.entity.dto;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
@JsonTypeInfo(use=JsonTypeInfo.Id.NAME,include=JsonTypeInfo.As.EXISTING_PROPERTY,property="action",visible=true)
@JsonSubTypes({
    @JsonSubTypes.Type(value=DepartmentUpdatePreviewDTO.class,name="UPDATE"),
    @JsonSubTypes.Type(value=DepartmentAddSecondaryPreviewDTO.class,name="ADD_SECONDARY"),
    @JsonSubTypes.Type(value=DepartmentEndMembershipPreviewDTO.class,name="END_MEMBERSHIP"),
    @JsonSubTypes.Type(value=DepartmentMoveMembersPreviewDTO.class,name="MOVE_MEMBERS"),
    @JsonSubTypes.Type(value=DepartmentMoveChildrenPreviewDTO.class,name="MOVE_CHILDREN"),
    @JsonSubTypes.Type(value=DepartmentDisablePreviewDTO.class,name="DISABLE"),
    @JsonSubTypes.Type(value=DepartmentEnablePreviewDTO.class,name="ENABLE"),
    @JsonSubTypes.Type(value=DepartmentArchivePreviewDTO.class,name="ARCHIVE")
})
public sealed interface DepartmentImpactPreviewDTO permits DepartmentUpdatePreviewDTO, DepartmentAddSecondaryPreviewDTO, DepartmentEndMembershipPreviewDTO, DepartmentMoveMembersPreviewDTO, DepartmentMoveChildrenPreviewDTO, DepartmentDisablePreviewDTO, DepartmentEnablePreviewDTO, DepartmentArchivePreviewDTO {
    String getAction();
    Integer getExpectedVersion();
}
