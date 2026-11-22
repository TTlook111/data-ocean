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
import com.dataocean.module.access.entity.vo.AccessScopeVO;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.feedback.entity.AssignIssueDTO;
import com.dataocean.module.feedback.entity.FeedbackIssueDetailVO;
import com.dataocean.module.feedback.entity.FeedbackIssueDetailVO.Report;
import com.dataocean.module.feedback.entity.FeedbackProtocol.IssueRow;
import com.dataocean.module.feedback.entity.ReviewIssueDTO;
import com.dataocean.module.feedback.mapper.FeedbackMapper;
import com.dataocean.module.feedback.mapper.FeedbackMapper.Activity;
import com.dataocean.module.feedback.mapper.FeedbackMapper.ActivityRow;
import com.dataocean.module.feedback.mapper.FeedbackMapper.Issue;
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
    private static final String VIEW = "feedback.view";
    private static final String PENDING = "PENDING_ASSIGNMENT";
    private static final String IN_PROGRESS = "IN_PROGRESS";
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

    /** 队列分页在真实责任过滤之后计算，基础行不带原问题。 */
    @Transactional
    public PageVO<IssueRow> issues(PageQuery query, String source, String issue, String scope) {
        guard.shared();
        permissions.require(VIEW);
        if (!Set.of("ALL", "MINE", "OWNED").contains(scope)) {
            throw BizIllegalException.invalid("无效的事项分组");
        }
        var page = feedback.list(
                query.<Issue>toFixedOrderMpPage(),
                Ids.nullable(source),
                Ids.nullable(issue),
                scope,
                UserContext.getUser(),
                permissions.has(ASSIGN),
                permissions.has(PROCESS));
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
                history);
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
                    normalized.rootCause()));
            // 核查自由文本只存在有单独权限裁剪的事项记录中。
            return List.of(new ChangeVO("review", "核查记录", null, "已记录"));
        });
    }

    /** 防重复提交、事项版本、业务记录和安全审计在同一个事务内完成。 */
    private MutationVO change(
            String id,
            String command,
            String action,
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
                .actionCode(action)
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
        return PENDING.equals(row.status()) || IN_PROGRESS.equals(row.status());
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
}
