package com.dataocean.module.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dataocean.common.security.IamPrincipal;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.access.entity.vo.AccessScopeVO;
import com.dataocean.module.access.entity.vo.AccessSourceOptionVO;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.feedback.mapper.FeedbackLifecycleMapper;
import com.dataocean.module.feedback.mapper.FeedbackNoticeMapper;
import com.dataocean.module.feedback.mapper.FeedbackNoticeMapper.Notice;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.OutboxMapper;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.query.mapper.QueryRequestMapper;
import com.dataocean.module.query.service.QueryService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/** 回告回看独立于首次投递，已读时也必须复核当前原问权限。 */
class FeedbackNoticeServiceTest {

    /** 收件人后来被撤权时只看到通用模板，已读事实仍保留。 */
    @Test
    void deliveredNoticeDoesNotDiscloseAnAnswerAfterAccessIsRevoked() {
        var mapper = mock(FeedbackNoticeMapper.class);
        var permissions = mock(PermissionService.class);
        var access = mock(AccessScopeService.class);
        var queries = mock(QueryService.class);
        var service = new FeedbackNoticeService(
                mapper,
                mock(QueryRequestMapper.class),
                mock(UserMapper.class),
                permissions,
                access,
                queries,
                mock(GuardMapper.class),
                mock(OutboxMapper.class),
                mock(JsonCodec.class),
                mock(TransactionTemplate.class),
                mock(FeedbackLifecycleMapper.class));
        var now = LocalDateTime.now(ZoneOffset.UTC);
        var notice = new Notice(
                UUID.randomUUID().toString(),
                8,
                1,
                7,
                UUID.randomUUID().toString(),
                1,
                "RESOLVED",
                "DELIVERED",
                now,
                now,
                now);
        var scope = new AccessScopeVO(new AccessSourceOptionVO("1", "验收库"), List.of(), List.of());
        when(mapper.own(notice.id(), 7)).thenReturn(notice);
        var otherRequest = UUID.randomUUID().toString();
        when(mapper.requests(notice.id())).thenReturn(List.of(notice.answerId(), otherRequest));
        when(permissions.effective(7)).thenReturn(Set.of("query.view", "query.ask"));
        when(access.findSource(7, 1)).thenReturn(Optional.of(scope), Optional.empty());
        when(queries.canOpenFeedbackAnswer(notice.answerId(), 7, scope)).thenReturn(true);

        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        new IamPrincipal(7L, 1L, "reader", "原提问人"), null, List.of()));
        try {
            var allowed = service.read(notice.id());
            assertThat(allowed.getRequestId()).isEqualTo(notice.answerId());
            assertThat(allowed.getRequestIds()).containsExactly(notice.answerId());
            assertThat(allowed.getMessage()).contains("通过复验");
            var restricted = service.read(notice.id());
            assertThat(restricted.getRequestId()).isNull();
            assertThat(restricted.getSourceId()).isNull();
            assertThat(restricted.getRequestIds()).isEmpty();
            assertThat(restricted.getMessage()).isEqualTo("该反馈已完成处理，详细结果受当前权限限制。");
            assertThat(restricted.getReadAt()).isEqualTo(allowed.getReadAt());
        } finally {
            // 不把测试登录身份留在复用线程中。
            SecurityContextHolder.clearContext();
        }
    }
}
