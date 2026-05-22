package com.dataocean.module.user.entity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 个人资料更新请求参数。
 * <p>
 * 用户自行修改个人信息时提交此对象，
 * 仅允许修改姓名、邮箱和手机号，不涉及角色和部门变更。
 * </p>
 *
 * @author dataocean
 */
@Data
public class ProfileUpdateDTO {

    /** 真实姓名（2-50位） */
    @Size(min = 2, max = 50, message = "真实姓名需为2-50位")
    private String realName;

    /** 电子邮箱 */
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 手机号码 */
    private String phone;
}
