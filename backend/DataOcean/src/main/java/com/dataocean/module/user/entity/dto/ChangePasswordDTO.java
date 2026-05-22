package com.dataocean.module.user.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 修改密码请求参数。
 * <p>
 * 用户主动修改密码时提交此对象，需提供旧密码进行身份验证，
 * 新密码需满足复杂度要求（8-32位，至少包含字母和数字）。
 * </p>
 *
 * @author dataocean
 */
@Data
public class ChangePasswordDTO {

    /** 旧密码（用于身份验证） */
    @NotBlank(message = "旧密码不能为空")
    private String oldPassword;

    /** 新密码（8-32位，至少包含字母和数字） */
    @NotBlank(message = "新密码不能为空")
    @Pattern(regexp = "^(?=.*[a-zA-Z])(?=.*\\d).{8,32}$", message = "新密码需为8-32位且至少包含字母和数字")
    private String newPassword;
}
