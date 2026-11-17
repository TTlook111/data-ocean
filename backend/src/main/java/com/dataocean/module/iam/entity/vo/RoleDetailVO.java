package com.dataocean.module.iam.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class RoleDetailVO {
    private String id;
    private String code;
    private String name;
    private String description;
    private String lifecycle;
    private boolean isBuiltin;
    private boolean containsProtected;
    private int memberCount;
    private int version;
    private Instant updatedAt;
    private List<String> permissionCodes;
}
