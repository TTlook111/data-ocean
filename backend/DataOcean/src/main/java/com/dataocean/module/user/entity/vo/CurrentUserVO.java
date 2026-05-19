package com.dataocean.module.user.entity.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CurrentUserVO {
    private Long id;
    private String username;
    private String realName;
    private String email;
    private String phone;
    private Boolean passwordChanged;
    private List<String> roles;
    private List<String> permissions;
}
