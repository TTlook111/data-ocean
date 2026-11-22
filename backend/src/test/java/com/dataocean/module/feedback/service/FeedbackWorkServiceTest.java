package com.dataocean.module.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.IamPrincipal;
import com.dataocean.infrastructure.Fingerprints;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.infrastructure.SecretKeyProvider;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.feedback.entity.ReviewIssueDTO;
import com.dataocean.module.feedback.mapper.FeedbackMapper;
import com.dataocean.module.feedback.mapper.FeedbackMapper.ActivityRow;
import com.dataocean.module.feedback.mapper.FeedbackMapper.Issue;
import com.dataocean.module.iam.mapper.CommandMapper;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.query.service.QueryService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** 覆盖事项办理权与业务证据隔离这一独立授权边界。 */
class FeedbackWorkServiceTest {

    /** 使用正在负责事项的合成身份。 */
    private static final long ACTOR = 7L;

    private final FeedbackMapper feedback = mock(FeedbackMapper.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final AccessScopeService access = mock(AccessScopeService.class);
    private final QueryService queries = mock(QueryService.class);
    private final AuditService audit = mock(AuditService.class);
    private final CommandMapper commands = mock(CommandMapper.class);
    private final FeedbackWorkService work = new FeedbackWorkService(
            feedback,
            mock(GuardMapper.class),
            permissions,
            mock(UserMapper.class),
            access,
            queries,
            commands,
            mock(Fingerprints.class),
            mock(SecretKeyProvider.class),
            mock(JsonCodec.class),
            audit);

    /** 模拟真实登录主体，避免跨测试残留线程身份。 */
    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        new IamPrincipal(ACTOR, 1L, "triage", "处理员"), null, List.of()));
    }

    /** 归还测试线程的身份上下文。 */
    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /** 非证据角色可办理事项，服务不会回查或回送正文。 */
    @Test
    void taskOwnershipDoesNotDiscloseEvidence() {
        var row = issue(ACTOR);
        when(feedback.issue(3L)).thenReturn(row);
        when(permissions.has("feedback.process")).thenReturn(true);
        var log = new ActivityRow(4, "REVIEWED", "原处理员", null, null, "原核查记录", "原根因", row.updatedAt());
        when(feedback.latestReview(3L, false)).thenReturn(log);
        when(feedback.activities(3L, false)).thenReturn(List.of(log));

        var detail = work.detail("3");
        assertThat(detail.canReview()).isTrue();
        assertThat(detail.evidenceRestricted()).isTrue();
        assertThat(detail.question()).isNull();
        assertThat(detail.reports()).isEmpty();
        assertThat(detail.latestReview().note()).isNull();
        assertThat(detail.activities().get(0).rootCause()).isNull();
        verifyNoInteractions(access, queries);
    }

    /** 其他负责人持有同一个角色也不能写入当前核查。 */
    @Test
    void reviewRejectsAnotherAssignee() {
        when(feedback.lockIssue(3L)).thenReturn(issue(8L));
        assertThatThrownBy(() -> work.review("3", UUID.randomUUID().toString(), new ReviewIssueDTO(4, "合成核查", null)))
                .isInstanceOf(BizIllegalException.class)
                .hasMessage("当前没有执行此操作的权限");
        verifyNoInteractions(audit);
    }

    /** 固定当前可办理、带确切版本的事项记录。 */
    private Issue issue(long assignee) {
        var instant = LocalDateTime.now(ZoneOffset.UTC);
        return new Issue(
                3L,
                1L,
                "库存",
                "USER_DISLIKE",
                UUID.randomUUID().toString(),
                "IN_PROGRESS",
                assignee,
                "负责人",
                4,
                "TECHNICAL",
                instant,
                instant);
    }
}
