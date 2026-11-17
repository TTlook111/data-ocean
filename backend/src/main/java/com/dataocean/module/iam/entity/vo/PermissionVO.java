package com.dataocean.module.iam.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class PermissionVO {
    private String code;
    private String name;
    private String purpose;
    private @com.fasterxml.jackson.annotation.JsonProperty("protected") boolean protectedAbility;
    private boolean enabled;
    private String pagePermissionCode;
}
