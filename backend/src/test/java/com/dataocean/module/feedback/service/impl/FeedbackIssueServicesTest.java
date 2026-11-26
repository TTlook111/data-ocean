package com.dataocean.module.feedback.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.IamPrincipal;
import com.dataocean.infrastructure.Fingerprints;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.infrastructure.SecretKeyProvider;
import com.dataocean.infrastructure.persistence.MybatisConfig;
import com.dataocean.module.access.entity.vo.AccessScopeVO;
import com.dataocean.module.access.entity.vo.AccessSourceOptionVO;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.feedback.entity.dto.AssignIssueDTO;
import com.dataocean.module.feedback.entity.dto.DisposeFeedbackSupplementDTO;
import com.dataocean.module.feedback.entity.dto.FeedbackIssueReasonDTO;
import com.dataocean.module.feedback.entity.dto.FeedbackProgressDTO;
import com.dataocean.module.feedback.entity.dto.RequestFeedbackSupplementDTO;
import com.dataocean.module.feedback.entity.dto.ReviewIssueDTO;
import com.dataocean.module.feedback.entity.dto.SourceDataResultDTO;
import com.dataocean.module.feedback.entity.dto.SubmitIssueResultDTO;
import com.dataocean.module.feedback.entity.dto.VerifyIssueDTO;
import com.dataocean.module.feedback.entity.query.FeedbackIssueQuery;
import com.dataocean.module.feedback.entity.vo.FeedbackClosurePreviewVO;
import com.dataocean.module.feedback.mapper.FeedbackLifecycleMapper;
import com.dataocean.module.feedback.mapper.FeedbackLifecycleMapper.Supplement;
import com.dataocean.module.feedback.mapper.FeedbackMapper;
import com.dataocean.module.feedback.mapper.FeedbackMapper.ActivityRow;
import com.dataocean.module.feedback.mapper.FeedbackMapper.Issue;
import com.dataocean.module.feedback.mapper.FeedbackQueryIssueMapper;
import com.dataocean.module.feedback.mapper.FeedbackResolutionMapper;
import com.dataocean.module.feedback.mapper.FeedbackResolutionMapper.Verification;
import com.dataocean.module.feedback.mapper.FeedbackTaskMapper;
import com.dataocean.module.feedback.mapper.FeedbackTaskMapper.Task;
import com.dataocean.module.feedback.service.FeedbackNoticeService;
import com.dataocean.module.iam.entity.po.CommandPO;
import com.dataocean.module.iam.entity.po.GuardPO;
import com.dataocean.module.iam.entity.po.UserPO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.mapper.CommandMapper;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper.Binding;
import com.dataocean.module.knowledge.service.KnowledgeVersionService;
import com.dataocean.module.query.service.QueryService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** 覆盖事项办理权与业务证据隔离这一独立授权边界。 */
class FeedbackIssueServicesTest {

    /** 使用正在负责事项的合成身份。 */
    private static final long ACTOR = 7L;

    private final FeedbackMapper feedback = mock(FeedbackMapper.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final AccessScopeService access = mock(AccessScopeService.class);
    private final QueryService queries = mock(QueryService.class);
    private final AuditService audit = mock(AuditService.class);
    private final CommandMapper commands = mock(CommandMapper.class);
    private final GuardMapper guard = mock(GuardMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final Fingerprints fingerprints = mock(Fingerprints.class);
    private final FeedbackResolutionMapper resolutions = mock(FeedbackResolutionMapper.class);
    private final FeedbackNoticeService notices = mock(FeedbackNoticeService.class);

    /**
     * 模拟主事项锁下可读的任务快照。
     */
    private final FeedbackTaskMapper tasks = mock(FeedbackTaskMapper.class);

    /**
     * 模拟独立的补充、主从关系和结案轮次。
     */
    private final FeedbackLifecycleMapper lifecycle = mock(FeedbackLifecycleMapper.class);

    /**
     * 使用受控发布引用验证治理任务的真实复验条件。
     */
    private final KnowledgeVersionService knowledge = mock(KnowledgeVersionService.class);

    /**
     * 模拟原提案的恒定时间指纹核对。
     */
    private final SecretKeyProvider secrets = mock(SecretKeyProvider.class);

    /**
     * 模拟已提交命令的回执解码。
     */
    private final JsonCodec json = mock(JsonCodec.class);

    /**
     * 命令回执和审计在两个办理服务中沿用相同实现。
     */
    private final FeedbackIssueCommands mutations = new FeedbackIssueCommands(
            feedback, guard, permissions, users, commands, fingerprints, secrets, json, audit);

    /**
     * 列表、详情及写入口共用真实复验资格判断。
     */
    private final FeedbackVerificationPolicy verification = new FeedbackVerificationPolicy(
            feedback,
            permissions,
            users,
            access,
            queries,
            fingerprints,
            resolutions,
            knowledge,
            mock(GovernanceCatalog.class),
            tasks,
            lifecycle);

    /**
     * 补充和主从关系使用与办理入口相同的权限及事务依赖。
     */
    private final FeedbackIssueLifecycleServiceImpl lifecycleWork = new FeedbackIssueLifecycleServiceImpl(
            feedback, guard, permissions, users, access, queries, notices, tasks, lifecycle, mutations);

    /**
     * 用真实读取服务组装当前队列和事项详情。
     */
    private final FeedbackIssueQueryServiceImpl issueQuery = new FeedbackIssueQueryServiceImpl(
            feedback,
            mock(FeedbackQueryIssueMapper.class),
            guard,
            permissions,
            access,
            queries,
            notices,
            tasks,
            verification,
            lifecycleWork);

    /**
     * 用真实办理服务检查分派、核查、复验和结案规则。
     */
    private final FeedbackWorkServiceImpl work = new FeedbackWorkServiceImpl(
            feedback, guard, permissions, queries, resolutions, notices, tasks, lifecycle, mutations, verification);

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
        var log = new ActivityRow(
                4, "REVIEWED", "原处理员", null, null, "原核查记录", "原根因", null, row.updatedAt(), null, null, 1, null);
        when(feedback.latestReview(3L, false)).thenReturn(log);
        when(feedback.activities(3L, false)).thenReturn(List.of(log));

        var detail = issueQuery.detail("3");
        assertThat(detail.isCanReview()).isTrue();
        assertThat(detail.isEvidenceRestricted()).isTrue();
        assertThat(detail.getQuestion()).isNull();
        assertThat(detail.getReports()).isEmpty();
        assertThat(detail.getLatestReview().getNote()).isNull();
        assertThat(detail.getActivities().get(0).getRootCause()).isNull();
        verifyNoInteractions(access, queries);
    }

    /** 其他负责人持有同一个角色也不能写入当前核查。 */
    @Test
    void reviewRejectsAnotherAssignee() {
        when(feedback.lockIssue(3L)).thenReturn(issue(8L));
        assertThatThrownBy(
                        () -> work.review("3", null, UUID.randomUUID().toString(), new ReviewIssueDTO(4, "合成核查", null)))
                .isInstanceOf(BizIllegalException.class)
                .hasMessage("当前没有执行此操作的权限");
        verifyNoInteractions(audit);
    }

    /** 复验失败真实退回处理中，不能接收到已解决回告。 */
    @Test
    void failedVerificationReturnsToProcessing() {
        var row = pending(null);
        when(feedback.lockIssue(3L)).thenReturn(row);
        eligibleActor();
        var scope = new AccessScopeVO(new AccessSourceOptionVO("1", "库存"), List.of(), List.of());
        when(access.findSource(ACTOR, 1L)).thenReturn(Optional.of(scope));
        when(queries.feedbackRuleSignature(scope)).thenReturn("规则签名");
        when(fingerprints.command(eq("feedback.verify.context"), eq("1"), any()))
                .thenReturn(new byte[32]);
        when(resolutions.verify(3L, 4, 5, "IN_PROGRESS")).thenReturn(1);
        when(resolutions.result(3L, 3))
                .thenReturn(new FeedbackResolutionMapper.ResultRecord(
                        3L, 3, ACTOR, "DISPLAY", "展示修复", "页面回查", null, null, row.updatedAt(), null, null));
        var receipt = work.verify(
                "3",
                null,
                UUID.randomUUID().toString(),
                new VerifyIssueDTO(4, "FAILED", "库存问数", "一次", "两次", "实际页面", null));
        assertThat(receipt.getVersion()).isEqualTo(5);
        verify(resolutions).verify(3L, 4, 5, "IN_PROGRESS");
        verifyNoInteractions(notices);
    }

    /** 曾经通过的复验在测试身份失去源授权后不能继续结案。 */
    @Test
    void successfulVerificationWithRevokedAccessCannotClose() {
        var row = pending(4);
        when(feedback.issue(3L)).thenReturn(row);
        eligibleActor();
        when(access.findSource(ACTOR, 1L)).thenReturn(Optional.empty());
        when(resolutions.verification(3L, 4))
                .thenReturn(new Verification(
                        3L, 4, 3, ACTOR, "原授权", "PASSED", "原问", "一次", "一次", "当前页面", row.updatedAt(), null));
        assertThatThrownBy(() -> work.closurePreview("3", "RESOLVED", null))
                .isInstanceOf(BizIllegalException.class)
                .hasMessage("当前处理结果尚未通过有效复验");
        verifyNoInteractions(notices);
    }

    /**
     * 第一次拆分带入原已提交结果，并撤销旧范围的复验资格。
     */
    @Test
    void addingKnowledgeTaskPreservesTechnicalResultAndInvalidatesVerification() {
        var row = pending(4);
        when(feedback.lockIssue(3L)).thenReturn(row);
        when(feedback.hasCurrentReview(3L, ACTOR)).thenReturn(true);
        when(tasks.advance(3L, 4, "IN_PROGRESS")).thenReturn(1);
        eligibleActor();

        var receipt = work.assignTask(
                "3", null, UUID.randomUUID().toString(), new AssignIssueDTO(4, "7", "KNOWLEDGE", "核对库存口径"));

        assertThat(receipt.getVersion()).isEqualTo(5);
        verify(tasks).insert(new Task(3L, 1, "TECHNICAL", ACTOR, null, null, "SUBMITTED", true, 3, null, null, false));
        verify(tasks)
                .insert(new Task(
                        3L, 5, "KNOWLEDGE", ACTOR, null, "核对库存口径", "IN_PROGRESS", false, null, null, null, false));
        verify(tasks).invalidate(3L);
        verifyNoInteractions(notices);
    }

    /**
     * 即使本人是主负责人，也不能替已分派给其他人的任务交付结果。
     */
    @Test
    void issueOwnerCannotSubmitOnBehalfOfOtherTaskAssignee() {
        var row = issue(ACTOR);
        var task = new Task(
                3L, 2, "TECHNICAL", 8L, "技术员", "展示修复", "IN_PROGRESS", true, null, null, row.updatedAt(), false);
        when(feedback.lockIssue(3L)).thenReturn(row);
        when(tasks.list(3L, true)).thenReturn(List.of(task));

        assertThatThrownBy(() -> work.submitResult(
                        "3",
                        2,
                        UUID.randomUUID().toString(),
                        new SubmitIssueResultDTO(4, "DISPLAY", "修正图例", "实际复查", null, true, null, false)))
                .isInstanceOf(BizIllegalException.class)
                .hasMessage("当前没有执行此操作的权限");
        verifyNoInteractions(resolutions, audit);
    }

    /**
     * 两项都提交后，一项复验通过仍不能为主事项办理已解决。
     */
    @Test
    void partiallyVerifiedTasksCannotClose() {
        var row = pending(null);
        when(feedback.issue(3L)).thenReturn(row);
        eligibleActor();
        var scope = new AccessScopeVO(new AccessSourceOptionVO("1", "库存"), List.of(), List.of());
        when(access.findSource(ACTOR, 1L)).thenReturn(Optional.of(scope));
        when(queries.feedbackRuleSignature(scope)).thenReturn("规则签名");
        when(fingerprints.command(eq("feedback.verify.context"), eq("1"), any()))
                .thenReturn(new byte[32]);
        when(tasks.list(3L, false))
                .thenReturn(List.of(
                        new Task(
                                3L, 1, "TECHNICAL", ACTOR, null, null, "SUBMITTED", true, 3, 4, row.updatedAt(), false),
                        new Task(
                                3L,
                                2,
                                "KNOWLEDGE",
                                ACTOR,
                                null,
                                null,
                                "SUBMITTED",
                                true,
                                2,
                                null,
                                row.updatedAt(),
                                false)));
        when(resolutions.verification(3L, 4))
                .thenReturn(new Verification(
                        3L, 4, 3, ACTOR, "00".repeat(32), "PASSED", "原问", "一次", "一次", "页面", row.updatedAt(), null));
        when(resolutions.result(3L, 3))
                .thenReturn(new FeedbackResolutionMapper.ResultRecord(
                        3L, 3, ACTOR, "DISPLAY", "展示修复", "页面回查", null, null, row.updatedAt(), null, null));

        assertThatThrownBy(() -> work.closurePreview("3", "RESOLVED", null))
                .isInstanceOf(BizIllegalException.class)
                .hasMessage("当前处理结果尚未通过有效复验");
        verify(resolutions).result(3L, 3);
        verifyNoInteractions(notices);
    }

    /**
     * 治理和技术任务都复验通过时统一进入结案，并要求治理轮次晚于任务范围调整。
     */
    @Test
    void verifiedKnowledgeAndTechnicalTasksPermitOneClosure() {
        var row = pending(null);
        var latestScopeChange = row.updatedAt().plusSeconds(1);
        var replayId = UUID.randomUUID().toString();
        var scope = new AccessScopeVO(new AccessSourceOptionVO("1", "库存"), List.of(), List.of());
        when(feedback.issue(3L)).thenReturn(row);
        eligibleActor();
        when(permissions.effective(ACTOR))
                .thenReturn(Set.of(
                        "feedback.view",
                        "feedback.process",
                        "feedback.verify",
                        "feedback.evidence",
                        "query.view",
                        "query.ask"));
        var technician = new UserPO();
        technician.setLifecycle("ENABLED");
        when(users.selectById(8L)).thenReturn(technician);
        when(permissions.effective(8L)).thenReturn(Set.of("feedback.view", "feedback.process"));
        when(access.findSource(ACTOR, 1L)).thenReturn(Optional.of(scope));
        when(queries.feedbackRuleSignature(scope)).thenReturn("规则签名");
        when(queries.canReplayFeedback(row.requestId(), scope)).thenReturn(true);
        when(fingerprints.command(eq("feedback.verify.context"), eq("1"), any()))
                .thenReturn(new byte[32]);

        // 两个结果分别保留实际修复方式，治理结果另须核对发布和重跑事实。
        when(tasks.list(3L, false))
                .thenReturn(List.of(
                        new Task(3L, 1, "TECHNICAL", 8L, null, null, "SUBMITTED", true, 3, 5, latestScopeChange, false),
                        new Task(
                                3L,
                                2,
                                "KNOWLEDGE",
                                ACTOR,
                                null,
                                null,
                                "SUBMITTED",
                                true,
                                4,
                                6,
                                latestScopeChange,
                                false)));
        when(resolutions.result(3L, 3))
                .thenReturn(new FeedbackResolutionMapper.ResultRecord(
                        3L, 3, 8L, "DISPLAY", "展示修复", "页面回查", null, null, row.updatedAt(), null, null));
        when(resolutions.result(3L, 4))
                .thenReturn(new FeedbackResolutionMapper.ResultRecord(
                        3L, 4, ACTOR, "KNOWLEDGE", "口径修复", "原问覆盖", null, null, row.updatedAt(), 90L, null));
        when(resolutions.verification(3L, 5))
                .thenReturn(new Verification(
                        3L, 5, 3, ACTOR, "00".repeat(32), "PASSED", "页面", "一次", "一次", "快照", row.updatedAt(), null));
        when(resolutions.verification(3L, 6))
                .thenReturn(new Verification(
                        3L, 6, 4, ACTOR, "00".repeat(32), "PASSED", "原问", "八箱", "八箱", "库存", row.updatedAt(), replayId));
        when(knowledge.current(1L, 90L))
                .thenReturn(new Binding(
                        90L,
                        1L,
                        1L,
                        2L,
                        3L,
                        4L,
                        5L,
                        1,
                        "库存口径",
                        "构建",
                        "验证",
                        6L,
                        null,
                        90L,
                        "知识集合",
                        ACTOR,
                        row.updatedAt(),
                        row.updatedAt(),
                        1L));
        when(queries.feedbackReplayMatches(replayId, row.requestId(), ACTOR, 1L, 90L, latestScopeChange, "规则签名", true))
                .thenReturn(true);
        var preview = new FeedbackClosurePreviewVO(4, "7", "原提问人", "RESOLVED", "已解决", List.of(), null);
        when(notices.preview(row, "RESOLVED")).thenReturn(preview);

        assertThat(work.closurePreview("3", "RESOLVED", null)).isEqualTo(preview);
    }

    /**
     * 等待源系统返工时撤回当前结果和复验，但保留版本台账。
     */
    @Test
    void waitingForSourceCorrectionRevokesPriorResultAndVerification() {
        var row = pending(4);
        var task = new Task(3L, 2, "SOURCE_DATA", ACTOR, null, "库存同步", "SUBMITTED", true, 3, 4, row.updatedAt(), false);
        when(feedback.lockIssue(3L)).thenReturn(row);
        when(tasks.list(3L, true)).thenReturn(List.of(task));
        when(tasks.advance(3L, 4, "IN_PROGRESS")).thenReturn(1);
        eligibleActor();

        work.followUpSourceData(
                "3", 2, UUID.randomUUID().toString(), new FeedbackProgressDTO(4, true, "源系统已重算样例库存", "等待同步后只读核对"));

        verify(tasks).update(task, ACTOR, "IN_PROGRESS", true, null, null, true);
        verify(tasks).invalidate(3L);
        verifyNoInteractions(resolutions, queries, notices);
    }

    /**
     * 技术单任务首次改回等待生效时保留处理责任，撤回当前结果及复验引用。
     */
    @Test
    void technicalProgressKeepsOwnerAndReturnsAReviewedResultToProcessing() {
        var row = pending(4);
        when(feedback.lockIssue(3L)).thenReturn(row);
        when(feedback.hasCurrentReview(3L, ACTOR)).thenReturn(true);
        when(tasks.advance(3L, 4, "IN_PROGRESS")).thenReturn(1);
        eligibleActor();

        var receipt = work.followUpTechnical(
                "3", null, UUID.randomUUID().toString(), new FeedbackProgressDTO(4, true, "修复已合入待发布", "发布后回看原问题"));

        assertThat(receipt.getVersion()).isEqualTo(5);
        verify(tasks)
                .insert(new Task(3L, 1, "TECHNICAL", ACTOR, null, null, "IN_PROGRESS", true, null, null, null, true));
        verify(tasks).invalidate(3L);
        verify(tasks)
                .appendActivity(
                        new FeedbackMapper.Activity(
                                3L, 5, ACTOR, "TECHNICAL_WAITING", ACTOR, "TECHNICAL", "修复已合入待发布", "发布后回看原问题", null),
                        1);
        verifyNoInteractions(resolutions, notices);
    }

    /**
     * 当前有效复验从复验人待办剔除，复验依据失效后重新进入同一查询条件的分页。
     */
    @Test
    void verificationTodoUsesCurrentVerificationBeforePaging() {
        var row = pending(4);
        var scope = new AccessScopeVO(new AccessSourceOptionVO("1", "库存"), List.of(), List.of());
        eligibleActor();
        when(permissions.has("feedback.verify")).thenReturn(true);
        when(permissions.has("query.view")).thenReturn(true);
        when(access.current(ACTOR)).thenReturn(List.of(scope));
        when(access.findSource(ACTOR, 1L)).thenReturn(Optional.of(scope));
        when(queries.feedbackRuleSignature(scope)).thenReturn("当前规则");
        when(resolutions.result(3L, 3))
                .thenReturn(new FeedbackResolutionMapper.ResultRecord(
                        3L, 3, ACTOR, "DISPLAY", "展示已修复", "回看页面", null, null, row.updatedAt(), null, null));
        when(fingerprints.command(eq("feedback.verify.context"), eq("1"), any()))
                .thenReturn(new byte[32]);
        when(resolutions.verification(3L, 4))
                .thenReturn(
                        new Verification(
                                3L,
                                4,
                                3,
                                ACTOR,
                                "00".repeat(32),
                                "PASSED",
                                "页面",
                                "一次",
                                "一次",
                                "截图",
                                row.updatedAt(),
                                null),
                        new Verification(
                                3L, 4, 3, ACTOR, "旧授权", "PASSED", "页面", "一次", "一次", "截图", row.updatedAt(), null));
        when(feedback.verificationCandidates(1L, 3L, null, null, "PENDING_VERIFICATION", null, List.of(1L), false))
                .thenReturn(List.of(row));
        when(feedback.list(
                        any(),
                        eq(1L),
                        eq(3L),
                        eq("MINE"),
                        eq(null),
                        eq(null),
                        eq("PENDING_VERIFICATION"),
                        eq(null),
                        eq(ACTOR),
                        eq(false),
                        eq(false),
                        any(),
                        eq(false)))
                .thenReturn(new Page<>());

        var filters = new FeedbackIssueQuery();
        filters.setSourceId("1");
        filters.setIssueId("3");
        filters.setScope("MINE");
        filters.setStatus("PENDING_VERIFICATION");
        issueQuery.issues(filters);
        issueQuery.issues(filters);

        verify(feedback)
                .list(
                        any(),
                        eq(1L),
                        eq(3L),
                        eq("MINE"),
                        eq(null),
                        eq(null),
                        eq("PENDING_VERIFICATION"),
                        eq(null),
                        eq(ACTOR),
                        eq(false),
                        eq(false),
                        eq(List.of()),
                        eq(false));
        verify(feedback)
                .list(
                        any(),
                        eq(1L),
                        eq(3L),
                        eq("MINE"),
                        eq(null),
                        eq(null),
                        eq("PENDING_VERIFICATION"),
                        eq(null),
                        eq(ACTOR),
                        eq(false),
                        eq(false),
                        eq(List.of(3L)),
                        eq(false));
    }

    /**
     * 使用项目分页拦截器验证候选 SQL，覆盖模拟 Mapper 无法发现的百条截断。
     */
    @Test
    void verificationCandidatesAreNotTruncatedByThePageLimit() {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(FeedbackMapper.class);
        var statement = configuration.getMappedStatement(FeedbackMapper.class.getName() + ".verificationCandidates");
        var params = Map.of("canClose", false, "verifySources", List.of(1L), "source", 1L);
        var sql = statement.getBoundSql(params);

        var interceptor =
                new MybatisConfig().mybatisPlusInterceptor().getInterceptors().get(0);
        ((PaginationInnerInterceptor) interceptor).beforeQuery(null, statement, params, RowBounds.DEFAULT, null, sql);

        assertThat(sql.getSql())
                .contains("i.status='PENDING_VERIFICATION'", "i.source_id=")
                .doesNotContain("LIMIT");
    }

    /**
     * 修正尚未生效时拒绝提交，生效后记录外部修正团队和记录人的不同责任。
     */
    @Test
    void sourceResultNeedsEffectiveConfirmationAndKeepsActualRepairer() {
        var row = issue(ACTOR);
        var waiting = new Task(
                3L, 2, "SOURCE_DATA", ACTOR, null, "库存同步", "IN_PROGRESS", true, null, null, row.updatedAt(), true);
        var submitted =
                new Task(3L, 2, "SOURCE_DATA", ACTOR, null, "库存同步", "SUBMITTED", true, 5, null, row.updatedAt(), false);
        when(feedback.lockIssue(3L)).thenReturn(row);
        when(tasks.list(3L, true)).thenReturn(List.of(waiting), List.of(submitted));
        when(tasks.advance(3L, 4, "PENDING_VERIFICATION")).thenReturn(1);
        eligibleActor();

        assertThatThrownBy(() -> work.submitSourceData(
                        "3",
                        2,
                        UUID.randomUUID().toString(),
                        new SourceDataResultDTO(4, "库存团队", "修正样例记录数量", "只读对账已核实", null, false)))
                .isInstanceOf(BizIllegalException.class);
        verifyNoInteractions(resolutions);
        work.submitSourceData(
                "3",
                2,
                UUID.randomUUID().toString(),
                new SourceDataResultDTO(4, "库存团队", "修正样例记录数量", "只读对账已核实", null, true));

        verify(resolutions)
                .appendResult(new FeedbackResolutionMapper.ResultRecord(
                        3L, 5, ACTOR, "SOURCE_DATA", "修正样例记录数量", "只读对账已核实", null, null, null, null, "库存团队"));
        verify(tasks).update(waiting, ACTOR, "SUBMITTED", true, 5, null, false);
        verifyNoInteractions(queries, notices);
    }

    /**
     * 即使处理结果齐备，原问的必要补充仍会拦截复验和结案。
     */
    @Test
    void pendingSupplementBlocksVerificationAndClosure() {
        var row = pending(4, true);
        when(feedback.issue(3L)).thenReturn(row);
        when(feedback.lockIssue(3L)).thenReturn(row);

        assertThatThrownBy(() -> work.closurePreview("3", "RESOLVED", null))
                .isInstanceOf(BizIllegalException.class)
                .hasMessageContaining("等待必要的用户补充");
        assertThatThrownBy(() -> work.verify(
                        "3",
                        null,
                        UUID.randomUUID().toString(),
                        new VerifyIssueDTO(4, "PASSED", "库存", "八箱", "八箱", "真实核对", null)))
                .isInstanceOf(BizIllegalException.class)
                .hasMessageContaining("等待必要的用户补充");
        verifyNoInteractions(resolutions, notices, audit);
    }

    /**
     * 处置不依赖原账号授权，其他等待仍生效，最后一条结束后继续处理。
     */
    @Test
    void disposingOneRequestReopensHandlingOnlyAfterTheLastPendingRequest() {
        var row = pending(4, true);
        String first = UUID.randomUUID().toString();
        String last = UUID.randomUUID().toString();
        when(feedback.lockIssue(3L)).thenReturn(row);
        when(lifecycle.supplement(first))
                .thenReturn(new Supplement(
                        first, 3, row.requestId(), "原请求", null, row.updatedAt(), null, null, null, null));
        when(lifecycle.supplement(last))
                .thenReturn(new Supplement(
                        last, 3, UUID.randomUUID().toString(), "补充原条件", null, row.updatedAt(), null, null, null, null));
        when(lifecycle.dispose(first, ACTOR, "WITHDRAWN", "已有单据可核实")).thenReturn(1);
        when(lifecycle.dispose(last, ACTOR, "UNAVAILABLE", "账号已停用")).thenReturn(1);
        when(lifecycle.groupWaiting(3L)).thenReturn(true, false);
        when(lifecycle.advance(3L, 4, true)).thenReturn(1);
        eligibleActor();

        lifecycleWork.disposeSupplement(
                "3", first, UUID.randomUUID().toString(), new DisposeFeedbackSupplementDTO(4, "WITHDRAWN", "已有单据可核实"));
        verify(lifecycle, never()).resumeHandling(3L);
        lifecycleWork.disposeSupplement(
                "3", last, UUID.randomUUID().toString(), new DisposeFeedbackSupplementDTO(4, "UNAVAILABLE", "账号已停用"));

        verify(lifecycle).resumeHandling(3L);
        verify(tasks, times(2)).invalidate(3L);
        verifyNoInteractions(access, queries, resolutions, notices);
    }

    /**
     * 提问人先回复时，负责人提交的处置不能覆盖这次实际回复。
     */
    @Test
    void disposalCannotOverwriteAConcurrentReply() {
        var row = issue(ACTOR);
        String id = UUID.randomUUID().toString();
        when(feedback.lockIssue(3L)).thenReturn(row);
        when(lifecycle.supplement(id))
                .thenReturn(new Supplement(
                        id, 3, row.requestId(), "原请求", "实际回复", row.createdAt(), row.updatedAt(), null, null, null));

        assertThatThrownBy(() -> lifecycleWork.disposeSupplement(
                        "3",
                        id,
                        UUID.randomUUID().toString(),
                        new DisposeFeedbackSupplementDTO(4, "UNAVAILABLE", "尚未联系到本人")))
                .isInstanceOf(BizIllegalException.class);
        verify(lifecycle, never()).advance(3L, 4, true);
        verifyNoInteractions(tasks, audit);
    }

    /**
     * 原问解除关联后仍能核实原提交，且异内容的请求不能取回该命令。
     */
    @Test
    void replaysSupplementReceiptAfterUnmerge() {
        String command = UUID.randomUUID().toString();
        var input = new RequestFeedbackSupplementDTO(4, UUID.randomUUID().toString(), "核对库存口径");
        var stored = new CommandPO();
        byte[] fingerprint = {1};
        stored.setPayloadHmac(fingerprint);
        stored.setState("COMMITTED");
        stored.setResultRef("receipt");
        var receipt = new MutationVO(command, "feedback_issue", "3", 5);
        when(lifecycle.origins(3L)).thenReturn(List.of());
        when(fingerprints.command("feedback.process", "3", input)).thenReturn(fingerprint);
        when(commands.find(Long.toString(ACTOR), "feedback.process", command)).thenReturn(stored);
        when(secrets.matches(fingerprint, fingerprint)).thenReturn(true);
        when(json.read("receipt", MutationVO.class)).thenReturn(receipt);

        assertThat(lifecycleWork.askSupplement("3", command, input)).isSameAs(receipt);
        var changed = new RequestFeedbackSupplementDTO(4, input.getRequestId(), "另一份提案");
        when(fingerprints.command("feedback.process", "3", changed)).thenReturn(new byte[] {2});
        assertThatThrownBy(() -> lifecycleWork.askSupplement("3", command, changed))
                .isInstanceOf(BizIllegalException.class)
                .hasMessage("同一操作编号的内容不同");

        verify(permissions, times(2)).require("feedback.process");
        verifyNoInteractions(feedback, audit);
    }

    /**
     * 重开只开启下一轮并撤回当期任务引用，旧回告和处理结果均不删除。
     */
    @Test
    void reopeningStartsAnotherRoundWithTheExistingTaskHistory() {
        var base = issue(ACTOR);
        var closed = new Issue(
                3L,
                1L,
                base.sourceName(),
                base.origin(),
                base.requestId(),
                "RESOLVED",
                ACTOR,
                base.assigneeName(),
                4,
                base.direction(),
                3,
                4,
                base.createdAt(),
                base.updatedAt(),
                base.summary(),
                1,
                null,
                1,
                false);
        when(feedback.lockIssue(3L)).thenReturn(closed);
        when(lifecycle.reopen(3L, 4)).thenReturn(1);
        eligibleActor();

        var receipt =
                lifecycleWork.reopen("3", UUID.randomUUID().toString(), new FeedbackIssueReasonDTO(4, "发现同范围复发证据"));

        assertThat(receipt.getVersion()).isEqualTo(5);
        verify(tasks).reopen(3L);
        verify(lifecycle).activity(3L, 5, ACTOR, "REOPENED", "发现同范围复发证据", null);
        verifyNoInteractions(resolutions, notices);
    }

    /** 为实际复验条件建立具有当前处理能力的测试账号。 */
    private void eligibleActor() {
        var user = new UserPO();
        user.setLifecycle("ENABLED");
        user.setAuthenticationVersion(1L);
        when(users.selectById(ACTOR)).thenReturn(user);
        when(permissions.effective(ACTOR))
                .thenReturn(Set.of("feedback.view", "feedback.process", "feedback.verify", "query.view"));
        when(guard.selectById(1)).thenReturn(new GuardPO());
    }

    /** 固定有原处理结果的待验证版本。 */
    private Issue pending(Integer verification) {
        return pending(verification, false);
    }

    /**
     * 在保持同一处理阶段的同时指定是否仍待必要补充。
     */
    private Issue pending(Integer verification, boolean waiting) {
        var row = issue(ACTOR);
        return new Issue(
                row.id(),
                row.sourceId(),
                row.sourceName(),
                row.origin(),
                row.requestId(),
                "PENDING_VERIFICATION",
                row.assigneeId(),
                row.assigneeName(),
                row.version(),
                row.direction(),
                3,
                verification,
                row.createdAt(),
                row.updatedAt(),
                row.summary(),
                row.referenceCount(),
                null,
                1,
                waiting);
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
                null,
                null,
                instant,
                instant,
                "问数回答收到反馈",
                1,
                null,
                1,
                false);
    }
}
