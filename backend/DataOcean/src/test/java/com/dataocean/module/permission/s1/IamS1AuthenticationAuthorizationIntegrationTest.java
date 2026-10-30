package com.dataocean.module.permission.s1;

import com.dataocean.common.security.JwtTokenProvider;
import com.dataocean.common.security.LoginUser;
import com.dataocean.common.security.UserDetailsServiceImpl;
import com.dataocean.module.permission.s1.mapper.IamS1FunctionMapper;
import com.dataocean.module.permission.s1.service.IamS1AuthorizationResolver;
import com.dataocean.module.metadata.scheduler.AutoSyncScheduler;
import com.dataocean.module.knowledge.scheduler.VectorIndexTaskScheduler;
import io.jsonwebtoken.Jwts;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B6-1：从 JWT 认证到 S1 Controller 的默认拒绝边界。
 * 不连接真实 MySQL；S1 Resolver 和 Redis 依赖均为测试替身。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:iam_s1_auth;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=never"
})
class IamS1AuthenticationAuthorizationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private IamS1AuthorizationResolver authorizationResolver;

    @MockBean
    private IamS1FunctionMapper functionMapper;

    @MockBean
    private AutoSyncScheduler autoSyncScheduler;

    @MockBean
    private VectorIndexTaskScheduler vectorIndexTaskScheduler;

    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(userDetailsService.loadUserByUsername("alice"))
                .thenReturn(new LoginUser(7L, "alice", "encoded", "Alice", List.of()));
        when(authorizationResolver.hasGlobalFunction(7L, "organization:permission:view"))
                .thenReturn(false);
    }

    @Test
    void authenticatedUserWithoutS1BindingGets403FromS1Controller() throws Exception {
        mockMvc.perform(get("/api/iam-s1/functions")
                        .header("Authorization", "Bearer " + newToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void legacyRolesPermissionsAndWildcardClaimsStillGet403FromS1Controller() throws Exception {
        mockMvc.perform(get("/api/iam-s1/functions")
                        .header("Authorization", "Bearer " + legacyClaimsToken()))
                .andExpect(status().isForbidden());
    }

    private String newToken() {
        LoginUser user = new LoginUser(7L, "alice", "encoded", "Alice", List.of());
        return jwtTokenProvider.generateToken(user, 0L);
    }

    private String legacyClaimsToken() {
        SecretKey key = (SecretKey) ReflectionTestUtils.getField(jwtTokenProvider, "secretKey");
        Instant now = Instant.now();
        return Jwts.builder()
                .id("legacy-jti")
                .subject("alice")
                .claim("uid", 7L)
                .claim("tokenVersion", 0L)
                .claim("roles", List.of("ADMIN"))
                .claim("permissions", List.of("*", "organization:permission:view"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(key)
                .compact();
    }
}
