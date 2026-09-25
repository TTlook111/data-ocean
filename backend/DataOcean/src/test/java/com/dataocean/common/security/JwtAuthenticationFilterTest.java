package com.dataocean.common.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtAuthenticationFilterTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void legacyClaimsCannotCreateBusinessAuthoritiesDuringAuthentication() throws Exception {
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        UserDetailsServiceImpl userDetailsService = mock(UserDetailsServiceImpl.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        FilterChain chain = mock(FilterChain.class);

        when(tokenProvider.getTokenId("legacy-token")).thenReturn("jti-1");
        when(tokenProvider.validateToken("legacy-token")).thenReturn(true);
        when(tokenProvider.getUserId("legacy-token")).thenReturn(7L);
        when(tokenProvider.getTokenVersion("legacy-token")).thenReturn(0L);
        when(tokenProvider.getUsernameFromToken("legacy-token")).thenReturn("alice");
        when(redis.hasKey("jwt:blacklist:jti-1")).thenReturn(false);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("user:token-version:7")).thenReturn(null);
        when(userDetailsService.loadUserByUsername("alice"))
                .thenReturn(new LoginUser(7L, "alice", "encoded", "Alice", List.of()));

        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(tokenProvider, userDetailsService, redis);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer legacy-token");

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .isEmpty();
    }
}
