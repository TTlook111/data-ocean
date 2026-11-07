package com.dataocean.module.versioning.entity.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.AssertTrue;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

/** 发布和撤回冻结用户看到的正式指针；显式 null 意味着该来源当时没有正式版本。 */
@Getter
public class SnapshotPointerWriteDTO {
    @Setter @NotBlank private String expectedStatus;
    @Positive private Long expectedPublishedSnapshotId;
    @Setter private String reason;
    private boolean pointerProvided;

    @JsonSetter("expectedPublishedSnapshotId")
    public void setExpectedPublishedSnapshotId(Long value) {
        this.expectedPublishedSnapshotId = value;
        this.pointerProvided = true;
    }

    @JsonIgnore
    @AssertTrue(message = "缺少原正式版本，请重新读取后再操作")
    public boolean isPointerProvided() { return pointerProvided; }
}
