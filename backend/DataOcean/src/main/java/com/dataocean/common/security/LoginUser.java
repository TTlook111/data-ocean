package com.dataocean.common.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;
import java.util.List;

@Getter
public class LoginUser extends User {

    private final Long userId;
    private final String realName;
    private final List<String> roles;
    private final List<String> permissions;

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
