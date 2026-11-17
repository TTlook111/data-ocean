package com.dataocean.module.iam.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class GrantLimitVO {
    private String roleId;
    private int version;
    private List<String> grantablePermissionCodes;
}
