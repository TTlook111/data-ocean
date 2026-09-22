package com.dataocean.common.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;

/**
 * 登录用户身份封装。
 * 业务角色和权限不放入认证对象，统一由 IAM-SIMPLE-1 动态解析。
 */
@Getter
public class LoginUser extends User {

    private final Long userId;
    private final String realName;

    public LoginUser(Long userId,
                     String username,
                     String password,
                     String realName,
                     Collection<? extends GrantedAuthority> authorities) {
        super(username, password, authorities);
        this.userId = userId;
        this.realName = realName;
    }
}
