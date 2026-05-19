package com.dataocean.common.security;

import com.dataocean.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserContextTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void currentUserReturnsLoginUserFromSecurityContext() {
        LoginUser loginUser = new LoginUser(
                7L,
                "admin",
                "password",
                "管理员",
                List.of("ADMIN"),
                List.of("user:manage"),
                List.of(new SimpleGrantedAuthority("user:manage"))
        );
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                loginUser,
                null,
                loginUser.getAuthorities()
        );

        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThat(UserContext.currentUser()).isSameAs(loginUser);
        assertThat(UserContext.currentUserId()).isEqualTo(7L);
        assertThat(UserContext.currentUsername()).isEqualTo("admin");
        assertThat(UserContext.currentRealName()).isEqualTo("管理员");
        assertThat(UserContext.currentRoles()).containsExactly("ADMIN");
        assertThat(UserContext.currentPermissions()).containsExactly("user:manage");
    }

    @Test
    void currentUserRejectsMissingLoginUserPrincipal() {
        assertThatThrownBy(UserContext::currentUser)
                .isInstanceOf(BusinessException.class)
                .hasMessage("未登录")
                .satisfies(exception ->
                        assertThat(((BusinessException) exception).getCode()).isEqualTo(401));
    }
}
