package com.dataocean.module.feedback.service;

import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.TextSearch;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.Fingerprints;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.infrastructure.SecretKeyProvider;
import com.dataocean.module.access.entity.vo.AccessOriginVO;
import com.dataocean.module.access.entity.vo.AccessScopeVO;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.feedback.entity.AssignIssueDTO;
import com.dataocean.module.feedback.entity.CloseIssueDTO;
import com.dataocean.module.feedback.entity.FeedbackIssueDetailVO;
import com.dataocean.module.feedback.entity.FeedbackIssueDetailVO.Report;
import com.dataocean.module.feedback.entity.FeedbackProtocol.IssueRow;
import com.dataocean.module.feedback.entity.ReviewIssueDTO;
import com.dataocean.module.feedback.entity.SubmitIssueResultDTO;
import com.dataocean.module.feedback.entity.VerifyIssueDTO;
import com.dataocean.module.feedback.mapper.FeedbackMapper;
import com.dataocean.module.feedback.mapper.FeedbackMapper.Activity;
import com.dataocean.module.feedback.mapper.FeedbackMapper.ActivityRow;
import com.dataocean.module.feedback.mapper.FeedbackMapper.Issue;
import com.dataocean.module.feedback.mapper.FeedbackResolutionMapper;
import com.dataocean.module.feedback.mapper.FeedbackResolutionMapper.ResultRecord;
import com.dataocean.module.feedback.mapper.FeedbackResolutionMapper.Verification;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.entity.vo.UserOptionVO;
import com.dataocean.module.iam.mapper.CommandMapper;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.query.service.QueryService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** 问题队列的当前责任、核查记录和权限裁剪。 */
@Service
@RequiredArgsConstructor
public class FeedbackWorkService {

    /** 动作同时用于授权、审计和原命令回查。 */
    private static final String ASSIGN = "feedback.assign";

    private static final String PROCESS = "feedback.process";
    private static final String VERIFY = "feedback.verify";
    private static final String CLOSE = "feedback.close";
    private static final String VIEW = "feedback.view";
    private static final String PENDING = "PENDING_ASSIGNMENT";
    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String TO_VERIFY = "PENDING_VERIFICATION";
    private static final String RESOLVED = "RESOLVED";
    private static final String CLOSED_OTHER = "CLOSED_OTHER";
    private static final Set<String> IMPACTS = Set.of("DISPLAY", "CONNECTION", "QUERY_SAFETY");
    private static final Set<String> OTHER_REASONS =
            Set.of("NOT_REPRODUCED", "DEFINITION_CONFIRMED", "CANCELLED", "OTHER");
    private static final Set<String> DIRECTIONS =
            Set.of("UNDETERMINED", "KNOWLEDGE", "TECHNICAL", "SOURCE_DATA", "DEFINITION", "ACCESS");

    /** 处理记录复用本模块统一台账。 */
    private final FeedbackMapper feedback;
    /** 与权限、人员状态和数据授权变化串行。 */
    private final GuardMapper guard;
    /** 候选人员提交时用实际使用资格而非前端列表校验。 */
    private final PermissionService permissions;
    /** 命令跨事项防重按操作人串行。 */
    private final UserMapper users;
    /** 当前来源的数据使用范围与分派独立。 */
    private final AccessScopeService access;
    /** 原答复和查询输出规则由问数模块负责。 */
    private final QueryService queries;
    /** 共用命令、受控指纹和成功审计。 */
    private final CommandMapper commands;

    private final Fingerprints fingerprints;
    private final SecretKeyProvider secrets;
    private final JsonCodec json;
    private final AuditService audit;
    /** 结果、复验与回告的当期事实。 */
    private final FeedbackResolutionMapper resolutions;

    private final FeedbackNoticeService notices;

    /** 队列分页在真实责任过滤之后计算，基础行不带原问题。 */
    @Transactional
    public PageVO<IssueRow> issues(PageQuery query, String source, String issue, String scope) {
        guard.shared();
        permissions.require(VIEW);
        if (!Set.of("ALL", "MINE", "OWNED", "CLOSED").contains(scope)) {
            throw BizIllegalException.invalid("无效的事项分组");
        }
        long actor = UserContext.getUser();
        var verifySources = "MINE".equals(scope) && permissions.has(VERIFY) && permissions.has("query.view")
                ? access.current(actor).stream()
                        .map(item -> Ids.parse(item.getSource().getId()))
                        .toList()
                : List.<Long>of();
        var page = feedback.list(
                query.<Issue>toFixedOrderMpPage(),
                Ids.nullable(source),
                Ids.nullable(issue),
                scope,
                actor,
                permissions.has(ASSIGN),
                permissions.has(PROCESS),
                verifySources,
                permissions.has(CLOSE));
        return PageVO.from(page, this::summary);
    }

    /** 分派候选只返回启用且同时具有反馈查看、处理权限的人员。 */
    @Transactional
    public PageVO<UserOptionVO> candidates(PageQuery query, String keyword) {
        guard.shared();
        permissions.require(ASSIGN);
        String pattern = keyword == null || keyword.isBlank() ? null : TextSearch.pattern(keyword);
        return PageVO.from(
                feedback.candidates(query.toFixedOrderMpPage(), pattern),
                user -> new UserOptionVO(
                        Ids.text(user.getId()),
                        user.getUsername(),
                        user.getDisplayName(),
                        "ENABLED",
                        user.getVersion(),
                        true));
    }

    /** 每次回看实时检查证据权限和源授权，基础历史则按事项查看权限显示。 */
    @Transactional
    public FeedbackIssueDetailVO detail(String id) {
        guard.shared();
        permissions.require(VIEW);
        var row = requireIssue(feedback.issue(Ids.parse(id)));
        long actor = UserContext.getUser();
        var scope = permissions.has("feedback.evidence") && permissions.has("query.view")
                ? access.findSource(actor, row.sourceId()).orElse(null)
                : null;
        var question = scope == null ? null : queries.feedbackQuestion(row.answerId(), scope);
        boolean evidenceAllowed = question != null;
        var reports = evidenceAllowed
                ? feedback.reports(row.id()).stream()
                        .map(report -> new Report(
                                report.id(), visibleText(scope, report.reason()), instant(report.occurredAt())))
                        .toList()
                : List.<Report>of();
        var last = feedback.latestReview(row.id(), evidenceAllowed);
        var history = feedback.activities(row.id(), evidenceAllowed).stream()
                .map(entry -> activity(evidenceAllowed ? scope : null, entry))
                .toList();
        return new FeedbackIssueDetailVO(
                summary(row),
                row.version(),
                row.direction(),
                assignable(row) && permissions.has(ASSIGN),
                owner(row, actor) && permissions.has(PROCESS),
                !evidenceAllowed,
                question,
                reports,
                last == null ? null : activity(evidenceAllowed ? scope : null, last),
                history,
                resolution(row, actor, evidenceAllowed ? scope : null));
    }

    /** 协调者分派或重新分派，在事项锁内确认目标人员资格。 */
    @Transactional
    public MutationVO assign(String id, String command, AssignIssueDTO input) {
        var normalized = new AssignIssueDTO(
                input.expectedVersion(),
                input.assigneeId(),
                input.direction(),
                requiredText(input.reason(), "请填写分派依据"));
        long assignee = Ids.parse(normalized.assigneeId());
        if (!DIRECTIONS.contains(normalized.direction())) {
            throw BizIllegalException.invalid("请选择有效的初步方向");
        }
        return change(id, command, ASSIGN, input.expectedVersion(), normalized, row -> {
            if (!assignable(row)) {
                throw BizIllegalException.state("当前状态不能分派");
            }
            var candidate = users.selectById(assignee);
            if (candidate == null
                    || !"ENABLED".equals(candidate.getLifecycle())
                    || candidate.isMustChangePassword()
                    || !permissions.effective(assignee).containsAll(Set.of(VIEW, PROCESS))) {
                throw BizIllegalException.state("所选人员当前不能办理反馈事项");
            }
            if (feedback.assign(row.id(), row.version(), assignee, normalized.direction()) != 1) {
                throw BizIllegalException.conflict();
            }
            feedback.appendActivity(new Activity(
                    row.id(),
                    row.version() + 1,
                    UserContext.getUser(),
                    "ASSIGNED",
                    assignee,
                    normalized.direction(),
                    normalized.reason(),
                    null,
                    null));
            return List.of(
                    new ChangeVO("status", "状态", row.status(), IN_PROGRESS),
                    new ChangeVO("assigneeId", "负责人编号", Ids.text(row.assigneeId()), Ids.text(assignee)),
                    new ChangeVO("direction", "初步方向", row.direction(), normalized.direction()));
        });
    }

    /** 只有当前负责人可记录本次核查，并发转派后旧输入不能提交。 */
    @Transactional
    public MutationVO review(String id, String command, ReviewIssueDTO input) {
        var normalized = new ReviewIssueDTO(
                input.expectedVersion(),
                requiredText(input.basis(), "请填写核查依据"),
                input.rootCause() == null || input.rootCause().isBlank()
                        ? null
                        : input.rootCause().strip());
        return change(id, command, PROCESS, input.expectedVersion(), normalized, row -> {
            long actor = UserContext.getUser();
            if (!owner(row, actor)) {
                throw BizIllegalException.denied();
            }
            if (feedback.review(row.id(), row.version(), actor) != 1) {
                throw BizIllegalException.conflict();
            }
            feedback.appendActivity(new Activity(
                    row.id(),
                    row.version() + 1,
                    actor,
                    "REVIEWED",
                    null,
                    null,
                    normalized.basis(),
                    normalized.rootCause(),
                    null));
            // 核查自由文本只存在有单独权限裁剪的事项记录中。
            return List.of(new ChangeVO("review", "核查记录", null, "已记录"));
        });
    }

    /** 当前技术负责人确认已生效后提交结果，等待独立的复验操作。 */
    @Transactional
    public MutationVO submitResult(String id, String command, SubmitIssueResultDTO input) {
        var impact = requiredText(input.impact(), "请选择修复影响范围");
        if (!IMPACTS.contains(impact) || !input.effectiveConfirmed()) {
            throw BizIllegalException.invalid("请选择影响范围并确认修复已生效");
        }
        boolean safety = "QUERY_SAFETY".equals(impact);
        if (safety && !Boolean.TRUE.equals(input.regressionPassed())) {
            throw BizIllegalException.invalid("查询与安全变更须确认相关场景测试已通过");
        }
        var normalized = new SubmitIssueResultDTO(
                input.expectedVersion(),
                impact,
                requiredText(input.summary(), "请填写修正说明"),
                requiredText(input.checkMethod(), "请填写复查方式与结果"),
                optionalText(input.reference()),
                true,
                safety ? requiredText(input.regression(), "请填写相关场景测试结果") : null,
                safety);
        return change(id, command, PROCESS, "feedback.result.submit", input.expectedVersion(), normalized, row -> {
            long actor = UserContext.getUser();
            if (!owner(row, actor)) {
                throw BizIllegalException.denied();
            }
            if (!"TECHNICAL".equals(row.direction()) || !feedback.hasCurrentReview(row.id(), actor)) {
                throw BizIllegalException.state("请先完成当前技术事项的核查记录");
            }
            int next = row.version() + 1;
            resolutions.appendResult(new ResultRecord(
                    row.id(),
                    next,
                    actor,
                    impact,
                    normalized.summary(),
                    normalized.checkMethod(),
                    normalized.reference(),
                    normalized.regression(),
                    null));
            if (resolutions.submit(row.id(), row.version(), next) != 1) {
                throw BizIllegalException.conflict();
            }
            feedback.appendActivity(
                    new Activity(row.id(), next, actor, "RESULT_SUBMITTED", null, null, "已提交处理结果", null, null));
            return List.of(new ChangeVO("status", "状态", row.status(), TO_VERIFY));
        });
    }

    /** 人工复验绑定当前处理结果与实际操作人的源权限，未通过返回处理。 */
    @Transactional
    public MutationVO verify(String id, String command, VerifyIssueDTO input) {
        var decision = requiredText(input.decision(), "请选择复验结论");
        if (!Set.of("PASSED", "FAILED").contains(decision)) {
            throw BizIllegalException.invalid("复验结论无效");
        }
        var normalized = new VerifyIssueDTO(
                input.expectedVersion(),
                decision,
                requiredText(input.testScope(), "请填写复验场景"),
                requiredText(input.expected(), "请填写期望结果"),
                requiredText(input.actual(), "请填写实际结果"),
                requiredText(input.dataBasis(), "请填写数据与复验依据"));
        return change(id, command, VERIFY, input.expectedVersion(), normalized, row -> {
            if (!TO_VERIFY.equals(row.status()) || row.resultVersion() == null || !eligibleHandler(row)) {
                throw BizIllegalException.state("当前处理结果或负责人资格已变化，请重新核查");
            }
            long verifier = UserContext.getUser();
            var context = verificationContext(verifier, row.sourceId());
            if (context == null) {
                throw BizIllegalException.denied();
            }
            boolean passed = "PASSED".equals(decision);
            String nextStatus = passed ? TO_VERIFY : IN_PROGRESS;
            int next = row.version() + 1;
            resolutions.appendVerification(new Verification(
                    row.id(),
                    next,
                    row.resultVersion(),
                    verifier,
                    context,
                    decision,
                    normalized.testScope(),
                    normalized.expected(),
                    normalized.actual(),
                    normalized.dataBasis(),
                    null));
            if (resolutions.verify(row.id(), row.version(), next, nextStatus) != 1) {
                throw BizIllegalException.conflict();
            }
            feedback.appendActivity(new Activity(
                    row.id(),
                    next,
                    verifier,
                    passed ? "VERIFIED" : "RECHECK_FAILED",
                    null,
                    null,
                    "已登记人工复验",
                    null,
                    null));
            return List.of(
                    new ChangeVO("verification", "复验结论", null, passed ? "通过" : "未通过"),
                    new ChangeVO("status", "状态", row.status(), nextStatus));
        });
    }

    /** 只读预览确认当前状态、原提问人和将提交的受控回告。 */
    @Transactional
    public FeedbackNoticeService.Preview closurePreview(String id, String outcome, String reason) {
        guard.shared();
        permissions.require(CLOSE);
        var row = requireIssue(feedback.issue(Ids.parse(id)));
        return notices.preview(row, closureTemplate(row, outcome, reason));
    }

    /** 结案与站内回告接收同时落盘，已解决只接受当期有效复验。 */
    @Transactional
    public MutationVO close(String id, String command, CloseIssueDTO input) {
        var normalized = new CloseIssueDTO(
                input.expectedVersion(),
                input.outcome(),
                optionalText(input.reason()),
                requiredText(input.conclusion(), "请填写结案结论"),
                requiredText(input.basis(), "请填写结案依据"),
                input.recipientId(),
                input.confirmedTemplate());
        return change(id, command, CLOSE, input.expectedVersion(), normalized, row -> {
            String template = closureTemplate(row, normalized.outcome(), normalized.reason());
            var preview = notices.preview(row, template);
            if (!preview.recipientId().equals(normalized.recipientId())
                    || !preview.template().equals(normalized.confirmedTemplate())) {
                throw BizIllegalException.conflict();
            }
            if (resolutions.close(row.id(), row.version(), normalized.outcome()) != 1) {
                throw BizIllegalException.conflict();
            }
            feedback.appendActivity(new Activity(
                    row.id(),
                    row.version() + 1,
                    UserContext.getUser(),
                    "CLOSED",
                    null,
                    null,
                    normalized.basis(),
                    null,
                    normalized.conclusion()));
            notices.enqueue(row, preview.recipientId(), template, command, row.version() + 1);
            return List.of(new ChangeVO("status", "状态", row.status(), normalized.outcome()));
        });
    }

    /** 展示当前结果和复验事实，技术负责人无证据权限时仍可办理。 */
    private FeedbackIssueDetailVO.Resolution resolution(Issue row, long actor, AccessScopeVO scope) {
        var result = row.resultVersion() == null ? null : resolutions.result(row.id(), row.resultVersion());
        var verification = currentVerification(row);
        boolean valid = validVerification(row, verification);
        boolean canClose = permissions.has(CLOSE);
        boolean canSubmit = owner(row, actor)
                && "TECHNICAL".equals(row.direction())
                && permissions.has(PROCESS)
                && feedback.hasCurrentReview(row.id(), actor);
        boolean canVerify = TO_VERIFY.equals(row.status())
                && row.resultVersion() != null
                && !valid
                && permissions.has(VERIFY)
                && eligibleHandler(row)
                && verificationContext(actor, row.sourceId()) != null;
        var summary = result == null
                ? null
                : new FeedbackIssueDetailVO.ProcessingResult(
                        result.version(),
                        result.impact(),
                        visibleText(scope, result.summary()),
                        visibleText(scope, result.checkMethod()),
                        visibleText(scope, result.referenceText()),
                        visibleText(scope, result.regression()),
                        instant(result.submittedAt()));
        var checked = verification == null
                ? null
                : new FeedbackIssueDetailVO.Recheck(
                        verification.version(),
                        verification.decision(),
                        valid,
                        visibleText(scope, verification.testScope()),
                        visibleText(scope, verification.expected()),
                        visibleText(scope, verification.actual()),
                        visibleText(scope, verification.dataBasis()),
                        instant(verification.verifiedAt()));
        var notice =
                RESOLVED.equals(row.status()) || CLOSED_OTHER.equals(row.status()) ? notices.status(row.id()) : null;
        var delivery = notice == null
                ? null
                : new FeedbackIssueDetailVO.NoticeStatus(
                        notice.state(), nullableInstant(notice.sentAt()), nullableInstant(notice.readAt()));
        return new FeedbackIssueDetailVO.Resolution(
                canSubmit,
                canVerify,
                canClose && valid && TO_VERIFY.equals(row.status()),
                canClose && canExplainClose(row),
                summary,
                checked,
                delivery);
    }

    /** 原结果、相关版本和测试身份的当前授权缺一不可。 */
    private boolean validVerification(Issue row, Verification record) {
        return record != null
                && "PASSED".equals(record.decision())
                && Objects.equals(row.resultVersion(), record.resultVersion())
                && eligibleHandler(row)
                && record.contextSignature().equals(verificationContext(record.verifierId(), row.sourceId()));
    }

    /** 每个人名下的来源和输出规则都要使用当前版本，比对时不展示凭据或业务数据。 */
    private String verificationContext(long tester, long source) {
        var user = users.selectById(tester);
        if (user == null || !"ENABLED".equals(user.getLifecycle()) || user.isMustChangePassword()) {
            return null;
        }
        var codes = permissions.effective(tester);
        if (!codes.containsAll(Set.of(VIEW, VERIFY, "query.view"))) {
            return null;
        }
        var scope = access.findSource(tester, source).orElse(null);
        if (scope == null) {
            return null;
        }
        var origins =
                scope.getOrigins().stream().map(AccessOriginVO::getId).sorted().toList();
        return HexFormat.of()
                .formatHex(fingerprints.command(
                        "feedback.verify.context",
                        Ids.text(source),
                        List.of(
                                user.getAuthenticationVersion(),
                                codes.contains("query.ask"),
                                codes.contains("query.sql"),
                                origins,
                                queries.feedbackRuleSignature(scope))));
    }

    /** 处理角色停用或调整后，需要重新分派并核对结果。 */
    private boolean eligibleHandler(Issue row) {
        if (row.assigneeId() == null) return false;
        var user = users.selectById(row.assigneeId());
        return user != null
                && "ENABLED".equals(user.getLifecycle())
                && !user.isMustChangePassword()
                && permissions.effective(row.assigneeId()).containsAll(Set.of(VIEW, PROCESS));
    }

    /** 按确切版本查人工复验。 */
    private Verification currentVerification(Issue row) {
        return row.verificationVersion() == null ? null : resolutions.verification(row.id(), row.verificationVersion());
    }

    /** 说明结案也须有本期核查作为依据。 */
    private boolean canExplainClose(Issue row) {
        return IN_PROGRESS.equals(row.status())
                && row.assigneeId() != null
                && feedback.hasCurrentReview(row.id(), row.assigneeId());
    }

    /** 已解决和其他结案有不同事实门槛和固定模板。 */
    private String closureTemplate(Issue row, String outcome, String reason) {
        if (RESOLVED.equals(outcome)) {
            if (!TO_VERIFY.equals(row.status()) || !validVerification(row, currentVerification(row))) {
                throw BizIllegalException.state("当前处理结果尚未通过有效复验");
            }
            return RESOLVED;
        }
        if (CLOSED_OTHER.equals(outcome) && reason != null && OTHER_REASONS.contains(reason) && canExplainClose(row)) {
            return reason;
        }
        throw BizIllegalException.state("请核对当前核查记录和其他结案原因");
    }

    /** 防重复提交、事项版本、业务记录和安全审计在同一个事务内完成。 */
    private MutationVO change(
            String id,
            String command,
            String action,
            int version,
            Object input,
            Function<Issue, List<ChangeVO>> apply) {
        return change(id, command, action, action, version, input, apply);
    }

    /** 处理结果与核查共用处理权限，审计按实际业务动作区分。 */
    private MutationVO change(
            String id,
            String command,
            String action,
            String auditAction,
            int version,
            Object input,
            Function<Issue, List<ChangeVO>> apply) {
        guard.shared();
        permissions.require(action);
        var key = commandId(command);
        long actor = UserContext.getUser();
        users.lock(actor);
        var row = requireIssue(feedback.lockIssue(Ids.parse(id)));
        String target = Ids.text(row.id());
        var signature = fingerprints.command(action, target, input);
        var known = commands.find(Ids.text(actor), action, key);
        if (known != null) {
            if (!secrets.matches(known.getPayloadHmac(), signature)) {
                throw new BizIllegalException(ErrorCode.COMMAND_CONTENT_CONFLICT, "同一操作编号的内容不同");
            }
            if (!"COMMITTED".equals(known.getState()) || known.getResultRef() == null) {
                throw new BizIllegalException(ErrorCode.COMMAND_IN_PROGRESS, "请核对原操作提交状态");
            }
            // 已提交命令只回原版本回执，不再次推进事项。
            return json.read(known.getResultRef(), MutationVO.class);
        }
        if (row.version() != version) {
            throw BizIllegalException.conflict();
        }
        commands.insert(actor, action, key, signature);
        var changes = apply.apply(row);
        audit.record(AuditWriteDTO.builder()
                .actionCode(auditAction)
                .targetType("feedback_issue")
                .targetId(target)
                .targetName("反馈事项 " + target)
                .commandId(key)
                .securityVersion(guard.selectById(1).getSecurityVersion())
                .changes(changes)
                .build());
        var receipt = new MutationVO(key, "feedback_issue", target, row.version() + 1);
        commands.complete(actor, action, key, json.write(receipt));
        return receipt;
    }

    /** 可继续办理的事项包括新线索以及现有处理中事项。 */
    private boolean assignable(Issue row) {
        return PENDING.equals(row.status()) || IN_PROGRESS.equals(row.status()) || TO_VERIFY.equals(row.status());
    }

    /** 权限与当前主负责人事实同时核对。 */
    private boolean owner(Issue row, long actor) {
        return IN_PROGRESS.equals(row.status()) && Objects.equals(row.assigneeId(), actor);
    }

    /** 无效编号不回显存储信息。 */
    private Issue requireIssue(Issue row) {
        if (row == null) {
            throw BizIllegalException.missing();
        }
        return row;
    }

    /** 基础信息在列表和详情保持一致。 */
    private IssueRow summary(Issue row) {
        return new IssueRow(
                Ids.text(row.id()),
                Ids.text(row.sourceId()),
                row.sourceName(),
                row.origin(),
                row.status(),
                Ids.text(row.assigneeId()),
                row.assigneeName(),
                instant(row.createdAt()),
                instant(row.updatedAt()));
    }

    /** 历史基础字段可用，原文独立检查是否可交付。 */
    private FeedbackIssueDetailVO.Activity activity(AccessScopeVO scope, ActivityRow row) {
        return new FeedbackIssueDetailVO.Activity(
                row.version(),
                row.action(),
                row.actor(),
                row.assignee(),
                row.direction(),
                visibleText(scope, row.note()),
                visibleText(scope, row.rootCause()),
                visibleText(scope, row.conclusion()),
                instant(row.occurredAt()));
    }

    /** 非获准业务正文不参与详情拼装。 */
    private String visibleText(AccessScopeVO scope, String text) {
        return scope == null ? null : queries.feedbackText(scope, text);
    }

    /** 避免空白核查和分派依据绕过客户端校验。 */
    private String requiredText(String text, String message) {
        if (text == null || text.isBlank()) {
            throw BizIllegalException.invalid(message);
        }
        return text.strip();
    }

    /** 可空业务文本按实际输入保存。 */
    private String optionalText(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    /** 公共命令表仅保存规范 UUID。 */
    private String commandId(String value) {
        try {
            if (value != null && UUID.fromString(value).toString().equals(value)) {
                return value;
            }
        } catch (IllegalArgumentException invalid) {
            // 只返回校验错误，不保存非规范输入。
        }
        throw BizIllegalException.invalid("操作编号须为 UUID");
    }

    /** MySQL 的平台时钟统一按 UTC 解释。 */
    private Instant instant(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }

    /** 尚未投递或阅读时保留空时间。 */
    private Instant nullableInstant(LocalDateTime value) {
        return value == null ? null : instant(value);
    }
}
