package com.dataocean.module.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class UserCreateRequest {
    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_]{4,50}$", message = "用户名需为4-50位字母、数字或下划线")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,32}$", message = "密码需为8-32位且至少包含字母和数字")
    private String password;

    @NotBlank(message = "真实姓名不能为空")
    @Size(min = 2, max = 50, message = "真实姓名需为2-50位")
    private String realName;

    @Email(message = "邮箱格式不正确")
    private String email;
    private String phone;
    private Long departmentId;

    @NotEmpty(message = "至少选择一个角色")
    private List<Long> roleIds;
}
