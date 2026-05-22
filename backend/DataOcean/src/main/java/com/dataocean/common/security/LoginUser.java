package com.dataocean.common.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;
import java.util.List;

/**
 * 登录用户信息封装类
 * <p>
 * 继承 Spring Security 的 User 类，扩展了用户 ID、真实姓名、
 * 角色列表和权限列表等业务字段，用于在安全上下文中携带完整的用户信息。
 * </p>
 */
@Getter
public class LoginUser extends User {

    /** 用户 ID（数据库主键） */
    private final Long userId;

    /** 用户真实姓名 */
    private final String realName;

    /** 用户角色编码列表 */
    private final List<String> roles;

    /** 用户权限标识列表 */
    private final List<String> permissions;

    /**
     * 构造登录用户对象
     *
     * @param userId      用户 ID
     * @param username    登录用户名
     * @param password    密码（已加密）
     * @param realName    真实姓名
     * @param roles       角色编码列表
     * @param permissions 权限标识列表
     * @param authorities Spring Security 权限集合
     */
    public LoginUser(Long userId,
                     String username,
                     String password,
                     String realName,
                     List<String> roles,
                     List<String> permissions,
                     Collection<? extends GrantedAuthority> authorities) {
        super(username, password, authorities);
        this.userId = userId;
        this.realName = realName;
        this.roles = roles;
        this.permissions = permissions;
    }
}
