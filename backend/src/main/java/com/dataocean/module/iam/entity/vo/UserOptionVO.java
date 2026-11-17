package com.dataocean.module.iam.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class UserOptionVO {
    private String id;
    private String username;
    private String displayName;
    private String displayStatus;
    private int version;
    private boolean selectable;
}
