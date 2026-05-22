package com.dataocean.module.user.entity.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 当前登录用户信息视图对象。
 * <p>
 * 用于 /api/auth/me 接口返回当前已认证用户的基本信息和权限数据，
 * 前端页面刷新时通过此接口恢复用户状态。
 * </p>
 *
 * @author dataocean
 */
@Data
@Builder
public class CurrentUserVO {

    /** 用户ID */
    private Long id;

    /** 登录用户名 */
    private String username;

    /** 用户真实姓名 */
    private String realName;

    /** 电子邮箱 */
    private String email;

    /** 手机号码 */
    private String phone;

    /** 是否已修改初始密码 */
    private Boolean passwordChanged;

    /** 用户拥有的角色编码列表 */
    private List<String> roles;

    /** 用户拥有的权限编码列表 */
    private List<String> permissions;
}
