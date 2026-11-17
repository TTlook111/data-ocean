package com.dataocean.module.iam.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class UserRefVO {
    private String id;
    private String username;
    private String displayName;
    private String displayStatus;
}
