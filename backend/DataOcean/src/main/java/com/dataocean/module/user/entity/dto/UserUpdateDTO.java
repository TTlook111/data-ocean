package com.dataocean.module.user.entity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 更新用户信息请求参数。
 * <p>
 * 管理员编辑用户信息时提交此对象，支持修改姓名、联系方式、
 * 部门归属和角色分配。不包含用户名和密码修改。
 * </p>
 *
 * @author dataocean
 */
@Data
public class UserUpdateDTO {

    /** 真实姓名（2-50位） */
    @Size(min = 2, max = 50, message = "真实姓名需为2-50位")
    private String realName;

    /** 电子邮箱 */
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 手机号码 */
    private String phone;

    /** 所属部门ID */
    private Long departmentId;

    /** 分配的角色ID列表 */
    private List<Long> roleIds;
}
