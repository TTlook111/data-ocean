package com.dataocean.module.iam.service.impl;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.DataOceanProperties;
import com.dataocean.infrastructure.Fingerprints;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.infrastructure.SecretKeyProvider;
import com.dataocean.module.iam.entity.dto.ReviewClaimsDTO;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.ImpactCountVO;
import com.dataocean.module.iam.entity.vo.ImpactPreviewVO;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.service.ReviewService;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;

import lombok.RequiredArgsConstructor;

/**
 * 签发并核验绑定操作者、提案和当前安全版本的影响核对凭据。
 */
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

    /**
     * 跨组织与权限变更共用的配置锁和安全版本存储。
     */
    private final GuardMapper guard;

    /**
     * 统一的明确类型 JSON 编解码适配。
     */
    private final JsonCodec json;

    /**
     * 类型化提案规范摘要和带密钥命令指纹能力。
     */
    private final Fingerprints fingerprints;

    /**
     * 受控密钥读取、HMAC 及恒定时间比较能力。
     */
    private final SecretKeyProvider secrets;

    /**
     * 统一 UTC 时钟，用于认证时限和核对凭据到期。
     */
    private final Clock clock;

    /**
     * 经校验的应用配置与受控资源引用。
     */
    private final DataOceanProperties properties;

    /**
     * 核对当前动作的完整提案、影响与版本，不在预览阶段写入业务事实。
     */
    public ImpactPreviewVO preview(
            String action,
            String target,
            Integer version,
            Object proposal,
            List<ChangeVO> changes,
            List<ImpactCountVO> affected,
            List<String> warnings) {
        String security = Long.toString(guard.current().getSecurityVersion());
        var claims = new ReviewClaimsDTO(
                Long.toString(UserContext.getUser()),
                action,
                target,
                version,
                security,
                clock.instant().getEpochSecond() + properties.getReviewSeconds(),
                fingerprints.proposal(proposal));
        String body = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.write(claims).getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(secrets.hmac(body));
        return new ImpactPreviewVO(
                action, target, version, security, body + "." + signature, changes, affected, warnings);
    }

    /**
     * 在正式提交时重核签名、主体、内容、当前版本和到期时间。
     */
    public void verify(String action, String target, Integer version, Object proposal, String token) {
        // 权威存储故障必须保留依赖错误语义，不混入客户端核对失效。
        String currentSecurityVersion = Long.toString(guard.current().getSecurityVersion());
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2
                    || !secrets.matches(
                            secrets.hmac(parts[0]), Base64.getUrlDecoder().decode(parts[1]))) {
                throw new IllegalArgumentException();
            }
            ReviewClaimsDTO claims = json.read(
                    new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8), ReviewClaimsDTO.class);
            if (!claims.getActorId().equals(Long.toString(UserContext.getUser()))
                    || !claims.getAction().equals(action)
                    || !Objects.equals(claims.getTargetId(), target)
                    || !Objects.equals(claims.getBaseVersion(), version)
                    || !claims.getSecurityVersion().equals(currentSecurityVersion)
                    || claims.getExpiresAt() <= clock.instant().getEpochSecond()
                    || !claims.getProposalDigest().equals(fingerprints.proposal(proposal))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | JacksonException error) {
            // 只转换令牌格式、签名或内容不匹配；数据库和内部配置异常继续向上传播。
            throw new BizIllegalException(ErrorCode.REVIEW_STALE, "核对依据已变化或失效，请重新核对");
        }
    }
}
