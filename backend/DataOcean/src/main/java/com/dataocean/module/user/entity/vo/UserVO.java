package com.dataocean.module.user.entity.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class UserVO {
    private Long id;
    private String username;
    private String realName;
    private String email;
    private String phone;
    private Long departmentId;
    private String departmentName;
    private List<String> roleNames;
    private List<String> roleCodes;
    private Integer status;
    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
}
