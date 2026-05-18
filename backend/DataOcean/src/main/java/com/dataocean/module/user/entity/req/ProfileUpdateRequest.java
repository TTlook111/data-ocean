package com.dataocean.module.user.entity.req;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ProfileUpdateRequest {

    @Size(min = 2, max = 50, message = "真实姓名需为2-50位")
    private String realName;

    @Email(message = "邮箱格式不正确")
    private String email;

    private String phone;
}
