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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {
    private final GuardMapper guard;
    private final JsonCodec json;
    private final Fingerprints fingerprints;
    private final SecretKeyProvider secrets;
    private final Clock clock;
    private final DataOceanProperties properties;
    public ImpactPreviewVO preview(String action,String target,Integer version,Object proposal,List<ChangeVO> changes,List<ImpactCountVO> affected,List<String> warnings) {
        String security=Long.toString(guard.current().getSecurityVersion());
        var claims=new ReviewClaimsDTO(Long.toString(UserContext.getUser()),action,target,version,security,
                clock.instant().getEpochSecond()+properties.getReviewSeconds(),fingerprints.proposal(proposal));
        String body=Base64.getUrlEncoder().withoutPadding().encodeToString(json.write(claims).getBytes(StandardCharsets.UTF_8));
        String signature=Base64.getUrlEncoder().withoutPadding().encodeToString(secrets.hmac(body));
        return new ImpactPreviewVO(action,target,version,security,body+"."+signature,changes,affected,warnings);
    }
    public void verify(String action,String target,Integer version,Object proposal,String token) {
        try {
            String[] parts=token.split("\\.",-1);
            if (parts.length!=2 || !secrets.matches(secrets.hmac(parts[0]),Base64.getUrlDecoder().decode(parts[1]))) throw new IllegalArgumentException();
            ReviewClaimsDTO claims=json.read(new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8),ReviewClaimsDTO.class);
            if (!claims.getActorId().equals(Long.toString(UserContext.getUser())) || !claims.getAction().equals(action)
                || !Objects.equals(claims.getTargetId(),target) || !Objects.equals(claims.getBaseVersion(),version)
                || !claims.getSecurityVersion().equals(Long.toString(guard.current().getSecurityVersion()))
                || claims.getExpiresAt()<=clock.instant().getEpochSecond() || !claims.getProposalDigest().equals(fingerprints.proposal(proposal))) throw new IllegalArgumentException();
        } catch (RuntimeException error) { throw new BizIllegalException(ErrorCode.REVIEW_STALE,"核对依据已变化或失效，请重新核对"); }
    }
}
