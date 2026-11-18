package com.dataocean.module.iam.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.IamPrincipal;
import com.dataocean.infrastructure.DataOceanProperties;
import com.dataocean.infrastructure.Fingerprints;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.infrastructure.SecretKeyProvider;
import com.dataocean.module.iam.entity.dto.RoleProfileDTO;
import com.dataocean.module.iam.entity.po.GuardPO;
import com.dataocean.module.iam.mapper.GuardMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import tools.jackson.databind.json.JsonMapper;

/**
 * 核对绑定与依赖故障分别处理，不能把数据库异常掩盖成客户端核对失效。
 */
class ReviewBoundaryTest {

    /**
     * 临时密钥材料不进入仓库或日志。
     */
    @TempDir
    Path temporary;

    /**
     * 当前权威安全版本的最小可控依赖。
     */
    private final GuardMapper guard = mock(GuardMapper.class);

    /**
     * 使用真实签名与规范化代码的核对服务。
     */
    private ReviewServiceImpl reviews;

    /**
     * 每个用例建立独立可信身份和固定 UTC 时钟。
     */
    @BeforeEach
    void prepare() throws IOException {
        Path file = temporary.resolve("key.txt");
        Files.writeString(file, "test-only-hmac-material-at-least-thirty-two-bytes");

        var secrets = new SecretKeyProvider(file.toString());

        var json = new JsonCodec(JsonMapper.builder().build());

        var state = new GuardPO();
        state.setSecurityVersion(1);
        when(guard.selectById(1)).thenReturn(state);

        var properties = new DataOceanProperties();
        properties.setReviewSeconds(300);
        reviews = new ReviewServiceImpl(
                guard,
                json,
                new Fingerprints(json, secrets),
                secrets,
                Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC),
                properties);

        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new IamPrincipal(1, 1, "test", "测试主体"), null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    /**
     * 测试线程结束清理身份，不能影响同线程的下一个测试主体。
     */
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 原始提案可以提交，内容变化或签名被改动必须拒绝。
     */
    @Test
    void bindsCompleteProposalAndSignature() {
        var profile = new RoleProfileDTO("角色", null, List.of("users.view"));

        var preview = reviews.preview("UPDATE", "10", 1, profile, List.of(), List.of(), List.of());
        assertDoesNotThrow(() -> reviews.verify("UPDATE", "10", 1, profile, preview.getReviewToken()));
        profile.setName("另一角色");
        assertThrows(
                BizIllegalException.class, () -> reviews.verify("UPDATE", "10", 1, profile, preview.getReviewToken()));
        assertThrows(BizIllegalException.class, () -> reviews.verify("UPDATE", "10", 1, profile, "invalid.signature"));
    }

    /**
     * 其他主体不能复用同一个目标与内容的核对凭据。
     */
    @Test
    void doesNotAllowAnotherActorToReuseReview() {
        var profile = new RoleProfileDTO("角色", null, List.of("users.view"));

        var preview = reviews.preview("UPDATE", "10", 1, profile, List.of(), List.of(), List.of());
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        new IamPrincipal(2, 1, "other", "另一主体"), null, List.of()));
        assertThrows(
                BizIllegalException.class, () -> reviews.verify("UPDATE", "10", 1, profile, preview.getReviewToken()));
    }

    /**
     * 权威依据读取失败应继续传播依赖错误，不得转换为 REVIEW_STALE。
     */
    @Test
    void preservesStorageFailureAtVerificationBoundary() {
        var profile = new RoleProfileDTO("角色", null, List.of("users.view"));

        var preview = reviews.preview("UPDATE", "10", 1, profile, List.of(), List.of(), List.of());
        when(guard.selectById(1)).thenThrow(new DataAccessResourceFailureException("test storage unavailable"));
        assertThrows(
                DataAccessResourceFailureException.class,
                () -> reviews.verify("UPDATE", "10", 1, profile, preview.getReviewToken()));
    }
}
