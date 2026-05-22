package com.dataocean.module.user.entity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建用户请求参数。
 * <p>
 * 管理员新建用户时提交此对象，包含账号凭据、基本信息和角色分配。
 * 新用户首次登录后需强制修改密码。
 * </p>
 *
 * @author dataocean
 */
@Data
public class UserCreateDTO {

    /** 登录用户名（4-50位字母、数字或下划线） */
    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_]{4,50}$", message = "用户名需为4-50位字母、数字或下划线")
    private String username;

    /** 初始密码（8-32位，至少包含字母和数字） */
    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,32}$", message = "密码需为8-32位且至少包含字母和数字")
    private String password;

    /** 真实姓名（2-50位） */
    @NotBlank(message = "真实姓名不能为空")
    @Size(min = 2, max = 50, message = "真实姓名需为2-50位")
    private String realName;

    /** 电子邮箱 */
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 手机号码 */
    private String phone;

    /** 所属部门ID */
    private Long departmentId;

    /** 分配的角色ID列表，至少选择一个 */
    @NotEmpty(message = "至少选择一个角色")
    private List<Long> roleIds;
}
