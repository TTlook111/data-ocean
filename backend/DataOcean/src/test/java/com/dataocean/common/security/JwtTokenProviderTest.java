package com.dataocean.common.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    @Test
    void newTokenContainsIdentityAndSessionClaimsButNoLegacyAuthorizationClaims() {
        JwtTokenProvider provider = new JwtTokenProvider(
                "01234567890123456789012345678901",
                3600L);
        LoginUser user = new LoginUser(
                7L,
                "alice",
                "encoded",
                "Alice",
                List.of(new SimpleGrantedAuthority("AUTHENTICATED_USER")));

        Claims claims = provider.parseClaims(provider.generateToken(user, 4L));

        assertThat(claims.get("uid", Number.class).longValue()).isEqualTo(7L);
        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat(claims.get("tokenVersion", Number.class).longValue()).isEqualTo(4L);
        assertThat(claims).doesNotContainKeys("roles", "permissions");
    }
}
