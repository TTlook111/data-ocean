package com.dataocean.module.user.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CurrentUserResponse {
    private Long id;
    private String username;
    private String realName;
    private List<String> roles;
    private List<String> permissions;
}
