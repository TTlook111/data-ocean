package com.dataocean.module.iam.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public final class LoginVO {
    private String userId;
    private String authenticationState;
    private boolean mustChangePassword;
    private Instant absoluteExpiresAt;
}
