package com.dataocean.module.user.entity.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class LoginVO {
    private String token;
    private String tokenType;
    private Long expiresIn;
    private Long userId;
    private String username;
    private String realName;
    private Boolean passwordChanged;
    private List<String> roles;
    private List<String> permissions;
}
