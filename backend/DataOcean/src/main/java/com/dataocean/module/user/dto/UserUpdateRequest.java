package com.dataocean.module.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class UserUpdateRequest {
    @Size(min = 2, max = 50, message = "真实姓名需为2-50位")
    private String realName;
    @Email(message = "邮箱格式不正确")
    private String email;
    private String phone;
    private Long departmentId;
    private List<Long> roleIds;
}
