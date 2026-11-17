package com.dataocean.module.iam.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class CurrentSessionVO {
    private String userId;
    private String username;
    private String displayName;
    private int version;
    private String authenticationState;
    private boolean mustChangePassword;
    private Instant absoluteExpiresAt;
    private List<String> permissionCodes;
    private String securityVersion;
}
