package com.dataocean.module.feedback.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.feedback.entity.AssignIssueDTO;
import com.dataocean.module.feedback.entity.ChangeFeedbackTaskDTO;
import com.dataocean.module.feedback.entity.CloseIssueDTO;
import com.dataocean.module.feedback.entity.DisposeFeedbackSupplementDTO;
import com.dataocean.module.feedback.entity.FeedbackIssueDetailVO;
import com.dataocean.module.feedback.entity.FeedbackIssueDetailVO.Report;
import com.dataocean.module.feedback.entity.FeedbackIssueReasonDTO;
import com.dataocean.module.feedback.entity.FeedbackLifecycleVO;
import com.dataocean.module.feedback.entity.FeedbackProgressDTO;
import com.dataocean.module.feedback.entity.FeedbackProtocol.IssueRow;
import com.dataocean.module.feedback.entity.FeedbackSupplementVO;
import com.dataocean.module.feedback.entity.FeedbackTaskVO;
import com.dataocean.module.feedback.entity.MergeFeedbackIssueDTO;
import com.dataocean.module.feedback.entity.RequestFeedbackSupplementDTO;
import com.dataocean.module.feedback.entity.ReviewIssueDTO;
import com.dataocean.module.feedback.entity.SourceDataResultDTO;
import com.dataocean.module.feedback.entity.SubmitFeedbackSupplementDTO;
import com.dataocean.module.feedback.entity.SubmitIssueResultDTO;
import com.dataocean.module.feedback.entity.SubmitKnowledgeResultDTO;
import com.dataocean.module.feedback.entity.VerifyIssueDTO;
import com.dataocean.module.feedback.mapper.FeedbackLifecycleMapper;
import com.dataocean.module.feedback.mapper.FeedbackLifecycleMapper.Origin;
import com.dataocean.module.feedback.mapper.FeedbackLifecycleMapper.Supplement;
import com.dataocean.module.feedback.mapper.FeedbackMapper;
import com.dataocean.module.feedback.mapper.FeedbackMapper.Activity;
import com.dataocean.module.feedback.mapper.FeedbackMapper.ActivityRow;
import com.dataocean.module.feedback.mapper.FeedbackMapper.Issue;
import com.dataocean.module.feedback.mapper.FeedbackQueryIssueMapper;
import com.dataocean.module.feedback.mapper.FeedbackResolutionMapper;
import com.dataocean.module.feedback.mapper.FeedbackResolutionMapper.ResultRecord;
import com.dataocean.module.feedback.mapper.FeedbackResolutionMapper.Verification;
import com.dataocean.module.feedback.mapper.FeedbackTaskMapper;
import com.dataocean.module.feedback.mapper.FeedbackTaskMapper.Task;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.entity.vo.UserOptionVO;
import com.dataocean.module.iam.mapper.CommandMapper;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper.Binding;
import com.dataocean.module.knowledge.service.impl.KnowledgeVersionUse;
import com.dataocean.module.query.service.QueryProtocol.Accepted;
import com.dataocean.module.query.service.QueryProtocol.SourceOption;
import com.dataocean.module.query.service.QueryService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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

    /**
     * 等待补充只覆盖展示和结案资格，保留真实任务办理阶段。
     */
    private static final String WAITING = "PENDING_SUPPLEMENT";

    /**
     * 重复事项完成合并后保留原记录并由主事项继续办理。
     */
    private static final String MERGED = "MERGED";

    private static final Set<String> IMPACTS = Set.of("DISPLAY", "CONNECTION", "QUERY_SAFETY");
    private static final Set<String> ORIGINS =
            Set.of("USER_DISLIKE", FeedbackQueryIssueIntake.KNOWLEDGE_GAP, FeedbackQueryIssueIntake.TECHNICAL_FAILURE);
    private static final Set<String> OTHER_REASONS =
            Set.of("NOT_REPRODUCED", "DEFINITION_CONFIRMED", "CANCELLED", "OTHER");
    private static final Set<String> DIRECTIONS =
            Set.of("UNDETERMINED", "KNOWLEDGE", "TECHNICAL", "SOURCE_DATA", "DEFINITION", "ACCESS");

    /**
     * 已接通实际处理和复验的任务方向。
     */
    private static final Set<String> TASK_DIRECTIONS = Set.of("KNOWLEDGE", "TECHNICAL", "SOURCE_DATA");

    /**
     * 平台记录源系统修正事实，使用既有只读问数或人工复查进行验收。
     */
    private static final String SOURCE_DATA = "SOURCE_DATA";

    /**
     * 任务已交付当前修复结果，仍需复验并由主事项统一结案。
     */
    private static final String SUBMITTED = "SUBMITTED";

    /** 处理记录复用本模块统一台账。 */
    private final FeedbackMapper feedback;
    /** 问数自动来源证据与点赞、点踩事件独立。 */
    private final FeedbackQueryIssueMapper queryIssues;
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

    /** 发布、暂停与复验资格沿用正式知识使用检查。 */
    private final KnowledgeVersionUse versions;

    private final GovernanceCatalog catalog;

    /**
     * 同一事项下的任务责任和当前结果引用。
     */
    private final FeedbackTaskMapper tasks;

    /**
     * 原问补充、主从关系和重开的持久状态。
     */
    private final FeedbackLifecycleMapper lifecycle;

    /**
     * 待验证事项先按当前复验资格筛选，列表和计数共享同一读快照。
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public PageVO<IssueRow> issues(
            PageQuery query,
            String source,
            String issue,
            String scope,
            String origin,
            String keyword,
            String status,
            String assignee) {
        guard.shared();
        permissions.require(VIEW);
        if (!Set.of("ALL", "MINE", "OWNED", "CLOSED", "MERGE").contains(scope)) {
            throw BizIllegalException.invalid("无效的事项分组");
        }
        if ("MERGE".equals(scope)) {
            permissions.require(ASSIGN);
            if (source == null) {
                throw BizIllegalException.invalid("请选择要核对的同源事项");
            }
        }
        if (origin != null && !ORIGINS.contains(origin)) {
            throw BizIllegalException.invalid("无效的事项来源");
        }
        if (status != null
                && !Set.of(PENDING, IN_PROGRESS, WAITING, TO_VERIFY, RESOLVED, CLOSED_OTHER, MERGED)
                        .contains(status)) {
            throw BizIllegalException.invalid("无效的事项状态");
        }
        long actor = UserContext.getUser();
        boolean mine = "MINE".equals(scope);
        boolean canClose = permissions.has(CLOSE);
        Long sourceId = Ids.nullable(source);
        Long issueId = Ids.nullable(issue);
        String keywordPattern = keyword == null || keyword.isBlank() ? null : TextSearch.pattern(keyword);
        String assigneePattern = assignee == null || assignee.isBlank() ? null : TextSearch.pattern(assignee);
        var verifySources = mine && permissions.has(VERIFY) && permissions.has("query.view")
                ? access.current(actor).stream()
                        .map(item -> Ids.parse(item.getSource().getId()))
                        .toList()
                : List.<Long>of();
        List<Long> verificationTodoIds = List.of();
        if (mine && (status == null || TO_VERIFY.equals(status)) && (canClose || !verifySources.isEmpty())) {
            // 只读取符合当前筛选的待验证候选，在主分页前核对实时的授权和发布状态。
            var candidates = feedback.list(
                    new Page<>(1, -1, false),
                    sourceId,
                    issueId,
                    "VERIFY_CANDIDATES",
                    origin,
                    keywordPattern,
                    status,
                    assigneePattern,
                    actor,
                    false,
                    false,
                    verifySources,
                    List.of(),
                    canClose);
            verificationTodoIds = candidates.getRecords().stream()
                    // 待验证行只在还有可复验或可结案动作时进入待办。
                    .filter(row -> verificationTodo(row, actor, canClose))
                    .map(Issue::id)
                    .toList();
        }
        var page = feedback.list(
                query.<Issue>toFixedOrderMpPage(),
                sourceId,
                issueId,
                scope,
                origin,
                keywordPattern,
                status,
                assigneePattern,
                actor,
                permissions.has(ASSIGN),
                permissions.has(PROCESS),
                List.of(),
                verificationTodoIds,
                canClose);
        return PageVO.from(page, this::summary);
    }

    /**
     * 待验证的主事项或独立任务使用详情同一套复验与结案判断。
     */
    private boolean verificationTodo(Issue row, long actor, boolean canClose) {
        var work = tasks.list(row.id(), false);
        if (work.isEmpty()) {
            // 旧单任务使用事项当前结果和复验引用。
            boolean valid = validVerification(row, null, currentVerification(row, null), false);
            return (canClose && valid) || canVerifyResult(row, null, currentResult(row, null), valid, actor);
        }
        return (canClose && verifiedTasks(row, work, false))
                || work.stream()
                        // 只要一项必要结果可以重新复验，复验人的待办就应恢复。
                        .anyMatch(task -> canVerifyResult(
                                row,
                                task,
                                currentResult(row, task),
                                validVerification(row, task, currentVerification(row, task), false),
                                actor));
    }

    /**
     * 已通过当前复验的结果退出复验待办，资格或依据变化后可以重新登记。
     */
    private boolean canVerifyResult(Issue row, Task task, ResultRecord result, boolean valid, long actor) {
        return !row.pendingSupplement()
                && TO_VERIFY.equals(row.status())
                && result != null
                && (task == null || SUBMITTED.equals(task.status()))
                && !valid
                && permissions.has(VERIFY)
                && eligibleHandler(row, task)
                && verificationContext(actor, row.sourceId()) != null;
    }

    /** 数据源筛选遵从与列表相同的查看权限。 */
    @Transactional
    public List<SourceOption> sources() {
        guard.shared();
        permissions.require(VIEW);
        return feedback.issueSources();
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

    /**
     * 每次回看实时检查证据及源授权，主事项与任务使用相同的裁剪结果。
     */
    @Transactional
    public FeedbackIssueDetailVO detail(String id) {
        guard.shared();
        permissions.require(VIEW);
        var row = requireIssue(feedback.issue(Ids.parse(id)));
        long actor = UserContext.getUser();
        var scope = permissions.has("feedback.evidence") && permissions.has("query.view")
                ? access.findSource(actor, row.sourceId()).orElse(null)
                : null;
        var question = scope == null ? null : queries.feedbackQuestion(row.requestId(), scope);
        boolean evidenceAllowed = question != null;
        boolean fromRating = "USER_DISLIKE".equals(row.origin());
        var reports = evidenceAllowed && fromRating
                ? feedback.reports(row.id()).stream()
                        .map(report -> new Report(
                                report.id(), visibleText(scope, report.reason()), instant(report.occurredAt())))
                        .toList()
                : List.<Report>of();
        var incidents = fromRating
                ? List.<FeedbackIssueDetailVO.QueryIncident>of()
                : queryIssues.incidents(row.id()).stream()
                        .map(entry -> new FeedbackIssueDetailVO.QueryIncident(
                                entry.requestId(),
                                entry.state(),
                                entry.phase(),
                                entry.diagnostic(),
                                entry.executionState(),
                                instant(entry.failedAt())))
                        .toList();
        var last = feedback.latestReview(row.id(), evidenceAllowed);
        var history = feedback.activities(row.id(), evidenceAllowed).stream()
                .map(entry -> activity(evidenceAllowed ? scope : null, entry))
                .toList();
        var work = tasks.list(row.id(), false);
        var visibleScope = evidenceAllowed ? scope : null;
        return new FeedbackIssueDetailVO(
                summary(row),
                row.version(),
                row.direction(),
                assignable(row) && permissions.has(ASSIGN),
                owner(row, actor) && permissions.has(PROCESS),
                !evidenceAllowed,
                question,
                reports,
                incidents,
                last == null ? null : activity(evidenceAllowed ? scope : null, last),
                history,
                resolution(row, null, actor, visibleScope, work),
                work.stream()
                        // 按任务逐项投影，沿用同一原问证据的裁剪结果。
                        .map(task -> taskDetail(row, task, actor, visibleScope))
                        .toList(),
                lifecycleDetail(row, actor, scope, work));
    }

    /**
     * 原问题分别核权后才返回接收人或补充正文，成员编号保留独立进入入口。
     */
    private FeedbackLifecycleVO lifecycleDetail(Issue row, long actor, AccessScopeVO scope, List<Task> work) {
        var readable = new LinkedHashSet<String>();
        var targets = new ArrayList<FeedbackLifecycleVO.Recipient>();
        var records = lifecycle.forIssue(row.id());
        var pending = records.stream()
                .filter(Supplement::pending)
                .map(Supplement::requestId)
                .collect(Collectors.toSet());
        if (scope != null) {
            // 同源原问仍可能使用不同的授权规则，逐条回查。
            for (var origin : lifecycle.origins(row.id())) {
                var question = queries.feedbackQuestion(origin.requestId(), scope);
                if (question != null) {
                    readable.add(origin.requestId());
                    if (!pending.contains(origin.requestId())) {
                        targets.add(new FeedbackLifecycleVO.Recipient(
                                Ids.text(origin.issueId()),
                                origin.requestId(),
                                origin.recipientName(),
                                question.question()));
                    }
                }
            }
        }
        boolean assigning = permissions.has(ASSIGN);
        boolean processing = permissions.has(PROCESS);
        return new FeedbackLifecycleVO(
                Ids.text(row.mergedInto()),
                lifecycle.members(row.id()).stream().map(Ids::text).toList(),
                canAskSupplement(row, actor) && processing ? targets : List.of(),
                records.stream()
                        .map(item -> supplement(item, readable.contains(item.requestId()) ? scope : null, true))
                        .toList(),
                processing && canHandleSupplement(row, actor),
                assigning && !readable.isEmpty() && canMerge(row, work),
                assigning && row.mergedInto() != null,
                processing && canReopen(row, actor),
                row.completionRound(),
                notices.rounds(row.id()));
    }

    /**
     * 主负责人结束一条待回复的请求。原账号的可用性不影响负责人继续跟进。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO disposeSupplement(
            String id, String supplementId, String command, DisposeFeedbackSupplementDTO input) {
        long issue = Ids.parse(id);
        var request = lifecycle.supplement(commandId(supplementId));
        if (request == null) {
            // 只接受确切的补充请求编号。
            throw BizIllegalException.missing();
        }
        String disposition = input.disposition();
        if (!"WITHDRAWN".equals(disposition) && !"UNAVAILABLE".equals(disposition)) {
            // 明确区别主动撤回和实际未能取得信息。
            throw BizIllegalException.invalid("请选择有效的补充处置方式");
        }
        var normalized = new DisposeFeedbackSupplementDTO(
                input.expectedVersion(), disposition, requiredText(input.reason(), "请填写处置原因"));
        Long peer = request.issueId() == issue ? null : request.issueId();
        return change(
                id,
                command,
                PROCESS,
                "feedback.supplement.dispose",
                input.expectedVersion(),
                List.of(request.id(), normalized),
                peer,
                (main, member) -> {
                    long actor = UserContext.getUser();
                    if (!canHandleSupplement(main, actor)) {
                        // 转派后只允许当前主负责人处置等待。
                        throw BizIllegalException.denied();
                    }
                    if (member != null && !Objects.equals(member.mergedInto(), main.id())) {
                        // 补充始终归原事项，纠正合并后的旧主事项不能代办。
                        throw BizIllegalException.missing();
                    }
                    if (lifecycle.dispose(request.id(), actor, disposition, normalized.reason()) != 1) {
                        // 提问人回复或另一处理已完成时，请据实重新查看。
                        throw BizIllegalException.conflict();
                    }
                    String note = "WITHDRAWN".equals(disposition) ? "已撤回补充请求" : "已记录无法取得补充";
                    recordSupplementCompletion(main, member, "SUPPLEMENT_ENDED", note);
                    return List.of(new ChangeVO("supplement", "用户补充", "待回复", note));
                });
    }

    /**
     * 一条请求结束后重新检查组内等待，保持已有结果并作废旧复验。
     */
    private void recordSupplementCompletion(Issue main, Issue member, String action, String note) {
        if (TO_VERIFY.equals(main.status()) && !lifecycle.groupWaiting(main.id())) {
            // 最后一条请求结束后继续处理，避免沿用旧复验结论。
            lifecycle.resumeHandling(main.id());
        }
        long actor = UserContext.getUser();
        advanceEvidence(main, true);
        lifecycle.activity(main.id(), main.version() + 1, actor, action, note, member == null ? null : member.id());
        if (member != null) {
            // 关联原事项也推进版本，原提问人已打开的旧表单随之失效。
            advanceEvidence(member, false);
            lifecycle.activity(member.id(), member.version() + 1, actor, action, note, main.id());
        }
    }

    /**
     * 主负责人向组内确切原问发出必要补充请求，两端版本与请求同事务推进。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO askSupplement(String id, String command, RequestFeedbackSupplementDTO input) {
        long issue = Ids.parse(id);
        var origin = relatedOrigin(issue, input.requestId());
        var normalized = new RequestFeedbackSupplementDTO(
                input.expectedVersion(), origin.requestId(), requiredText(input.prompt(), "请填写具体缺项"));
        Long peer = origin.issueId() == issue ? null : origin.issueId();
        return change(
                id,
                command,
                PROCESS,
                "feedback.supplement.ask",
                input.expectedVersion(),
                normalized,
                peer,
                (row, member) -> {
                    long actor = UserContext.getUser();
                    if (!canAskSupplement(row, actor)) {
                        throw BizIllegalException.denied();
                    }
                    var current = relatedOrigin(row.id(), origin.requestId());
                    if (current.issueId() != origin.issueId()
                            || (member != null && !Objects.equals(member.mergedInto(), row.id()))) {
                        throw BizIllegalException.conflict();
                    }
                    requireEvidence(row.sourceId(), current.requestId());
                    var recipientScope = ownQueryScope(current, row.sourceId());
                    if (visibleText(recipientScope, normalized.prompt()) == null) {
                        throw BizIllegalException.state("补充问题超出原提问人当前可见范围");
                    }
                    if (lifecycle.pending(current.requestId()) != null) {
                        throw BizIllegalException.state("该原问题已有待补充请求");
                    }
                    lifecycle.appendSupplement(
                            UUID.randomUUID().toString(),
                            current.issueId(),
                            current.requestId(),
                            normalized.prompt(),
                            null);
                    if (member != null) {
                        advanceEvidence(member, false);
                        lifecycle.activity(
                                member.id(), member.version() + 1, actor, "SUPPLEMENT_ASKED", "已请求补充", row.id());
                    }
                    advanceEvidence(row, true);
                    lifecycle.activity(row.id(), row.version() + 1, actor, "SUPPLEMENT_ASKED", "已请求补充", peer);
                    return List.of(new ChangeVO("status", "状态", displayStatus(row), WAITING));
                });
    }

    /**
     * 员工只读取本次原问关联的内容，后续以当前主事项状态表达处理进度。
     */
    @Transactional
    public FeedbackSupplementVO ownSupplement(String requestId) {
        guard.shared();
        permissions.require("query.view");
        var origin = lifecycle.ownOrigin(commandId(requestId), UserContext.getUser());
        if (origin == null) {
            return null;
        }
        var original = requireIssue(feedback.issue(origin.issueId()));
        var scope = ownQueryScope(origin, original.sourceId());
        var entries = lifecycle.forRequest(origin.requestId());
        var main = original.mergedInto() == null ? original : requireIssue(feedback.issue(original.mergedInto()));
        boolean canSubmit = assignable(main)
                && (FeedbackQueryIssueIntake.KNOWLEDGE_GAP.equals(origin.origin())
                        || entries.stream().anyMatch(Supplement::pending));
        return new FeedbackSupplementVO(
                original.version(),
                displayStatus(main),
                canSubmit,
                entries.stream().map(item -> supplement(item, scope, false)).toList());
    }

    /**
     * 回复原请求或主动补充本人知识缺口，正文只进入反馈证据。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO submitSupplement(String requestId, String command, SubmitFeedbackSupplementDTO input) {
        guard.shared();
        permissions.require("query.view");
        long actor = UserContext.getUser();
        var origin = lifecycle.ownOrigin(commandId(requestId), actor);
        if (origin == null) {
            throw BizIllegalException.missing();
        }
        var snapshot = requireIssue(feedback.issue(origin.issueId()));
        ownQueryScope(origin, snapshot.sourceId());
        var answer = requiredText(input.answer(), "请填写业务补充说明");
        var normalized =
                new SubmitFeedbackSupplementDTO(input.expectedVersion(), optionalText(input.supplementId()), answer);
        return change(
                Ids.text(origin.issueId()),
                command,
                "query.view",
                "feedback.supplement.submit",
                input.expectedVersion(),
                List.of(origin.requestId(), normalized),
                snapshot.mergedInto(),
                (row, parent) -> {
                    if (!Objects.equals(snapshot.mergedInto(), row.mergedInto())) {
                        throw BizIllegalException.conflict();
                    }
                    var main = parent == null ? row : parent;
                    ownQueryScope(origin, row.sourceId());
                    if (!assignable(main)) {
                        throw BizIllegalException.state("当前反馈事项已结束");
                    }
                    var pending = lifecycle.pending(origin.requestId());
                    if (pending != null) {
                        // 旧草稿不能答复后来新建的请求。
                        if (!Objects.equals(pending.id(), normalized.supplementId())
                                || lifecycle.answer(pending.id(), origin.requestId(), answer) != 1) {
                            throw BizIllegalException.conflict();
                        }
                    } else {
                        if (normalized.supplementId() != null
                                || !FeedbackQueryIssueIntake.KNOWLEDGE_GAP.equals(origin.origin())) {
                            throw BizIllegalException.state("当前原问题没有可补充的请求");
                        }
                        lifecycle.appendSupplement(
                                UUID.randomUUID().toString(), row.id(), origin.requestId(), null, answer);
                    }
                    recordSupplementCompletion(main, parent == null ? null : row, "SUPPLEMENT_ADDED", "已接收原提问人的补充");
                    return List.of(new ChangeVO("supplement", "用户补充", null, "已收到"));
                });
    }

    /**
     * 同源且经人工核实的重复事项归入一个仍在办理的主事项。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO merge(String id, String command, MergeFeedbackIssueDTO input) {
        long target = Ids.parse(input.targetId());
        if (Ids.parse(id) == target) {
            throw BizIllegalException.invalid("主事项和重复事项须为不同记录");
        }
        var normalized = new MergeFeedbackIssueDTO(
                input.expectedVersion(),
                Ids.text(target),
                input.targetVersion(),
                requiredText(input.reason(), "请填写相同根因和处理范围的依据"));
        return change(
                id,
                command,
                ASSIGN,
                "feedback.issue.merge",
                input.expectedVersion(),
                normalized,
                target,
                (row, main) -> {
                    if (main.version() != input.targetVersion()) {
                        throw BizIllegalException.conflict();
                    }
                    if (main.sourceId() != row.sourceId()
                            || !assignable(main)
                            || !canMerge(row, tasks.list(row.id(), true))) {
                        throw BizIllegalException.state("请选择同源的未结主事项及尚无独立处理结果的重复事项");
                    }
                    requireEvidence(row.sourceId(), row.requestId());
                    requireEvidence(main.sourceId(), main.requestId());
                    if (lifecycle.merge(row.id(), row.version(), main.id()) != 1) {
                        throw BizIllegalException.conflict();
                    }
                    advanceEvidence(main, true);
                    long actor = UserContext.getUser();
                    lifecycle.activity(row.id(), row.version() + 1, actor, "MERGED", normalized.reason(), main.id());
                    lifecycle.activity(
                            main.id(), main.version() + 1, actor, "MEMBER_ADDED", normalized.reason(), row.id());
                    return List.of(new ChangeVO("mainIssueId", "主事项编号", null, Ids.text(main.id())));
                });
    }

    /**
     * 解除错误合并，原事项的补充、证据和关系历史均继续归原事项。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO unmerge(String id, String command, FeedbackIssueReasonDTO input) {
        var snapshot = requireIssue(feedback.issue(Ids.parse(id)));
        var normalized =
                new FeedbackIssueReasonDTO(input.expectedVersion(), requiredText(input.reason(), "请填写纠正合并的依据"));
        return change(
                id,
                command,
                ASSIGN,
                "feedback.issue.unmerge",
                input.expectedVersion(),
                normalized,
                snapshot.mergedInto(),
                (row, main) -> {
                    if (main == null || !Objects.equals(row.mergedInto(), main.id()) || !MERGED.equals(row.status())) {
                        throw BizIllegalException.state("当前事项没有可纠正的合并关系");
                    }
                    if (lifecycle.unmerge(row.id(), row.version(), main.id()) != 1) {
                        throw BizIllegalException.conflict();
                    }
                    advanceEvidence(main, assignable(main));
                    long actor = UserContext.getUser();
                    lifecycle.activity(row.id(), row.version() + 1, actor, "UNMERGED", normalized.reason(), main.id());
                    lifecycle.activity(
                            main.id(), main.version() + 1, actor, "MEMBER_REMOVED", normalized.reason(), row.id());
                    return List.of(new ChangeVO("mainIssueId", "主事项编号", Ids.text(main.id()), null));
                });
    }

    /**
     * 根据新证据开启新完成轮次，复验、任务结果及旧回告继续保留在历史中。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO reopen(String id, String command, FeedbackIssueReasonDTO input) {
        var normalized =
                new FeedbackIssueReasonDTO(input.expectedVersion(), requiredText(input.reason(), "请填写新证据或同范围复发依据"));
        return change(id, command, PROCESS, "feedback.issue.reopen", input.expectedVersion(), normalized, row -> {
            long actor = UserContext.getUser();
            if (!canReopen(row, actor)) {
                throw BizIllegalException.denied();
            }
            if (lifecycle.reopen(row.id(), row.version()) != 1) {
                throw BizIllegalException.conflict();
            }
            tasks.reopen(row.id());
            lifecycle.activity(row.id(), row.version() + 1, actor, "REOPENED", normalized.reason(), null);
            return List.of(
                    new ChangeVO("status", "状态", row.status(), IN_PROGRESS),
                    new ChangeVO(
                            "completionRound",
                            "完成轮次",
                            Integer.toString(row.completionRound()),
                            Integer.toString(row.completionRound() + 1)));
        });
    }

    /**
     * 新证据与关系变动在锁内推进版本，撤销相应的当前复验指针。
     */
    private void advanceEvidence(Issue row, boolean invalidate) {
        if (lifecycle.advance(row.id(), row.version(), invalidate) != 1) {
            throw BizIllegalException.conflict();
        }
        if (invalidate) {
            tasks.invalidate(row.id());
        }
    }

    /**
     * 只有可回查原问的协调者可以将未开始独立处理的事项作为重复项。
     */
    private boolean canMerge(Issue row, List<Task> work) {
        return assignable(row)
                && row.resultVersion() == null
                && work.isEmpty()
                && lifecycle.members(row.id()).isEmpty();
    }

    /**
     * 已核查的主负责人可在办理阶段联系原提问人。
     */
    private boolean canAskSupplement(Issue row, long actor) {
        return canHandleSupplement(row, actor) && feedback.hasCurrentReview(row.id(), actor);
    }

    /**
     * 找到当前未结主事项的实际负责人，已合并的原事项不独立处置。
     */
    private boolean canHandleSupplement(Issue row, long actor) {
        return assignable(row) && Objects.equals(row.assigneeId(), actor);
    }

    /**
     * 主负责人或获派单功能的办理人员可重开独立结案事项。
     */
    private boolean canReopen(Issue row, long actor) {
        return (RESOLVED.equals(row.status()) || CLOSED_OTHER.equals(row.status()))
                && (Objects.equals(row.assigneeId(), actor) || permissions.has(ASSIGN));
    }

    /**
     * 从主事项和稳定原问关系定位补充目标。
     */
    private Origin relatedOrigin(long issue, String request) {
        return lifecycle.origins(issue).stream()
                .filter(origin -> origin.requestId().equals(request))
                .findFirst()
                .orElseThrow(BizIllegalException::missing);
    }

    /**
     * 证据读取能力和原问题的当前业务范围缺一不可。
     */
    private void requireEvidence(long source, String request) {
        permissions.require("feedback.evidence");
        permissions.require("query.view");
        var scope = access.findSource(UserContext.getUser(), source).orElse(null);
        if (scope == null || queries.feedbackQuestion(request, scope) == null) {
            throw BizIllegalException.denied();
        }
    }

    /**
     * 用户端补充和后台请求均复核原提问账号的当前可见范围。
     */
    private AccessScopeVO ownQueryScope(Origin origin, long source) {
        var account = users.selectById(origin.recipientId());
        var scope = access.findSource(origin.recipientId(), source).orElse(null);
        if (account == null
                || !"ENABLED".equals(account.getLifecycle())
                || account.isMustChangePassword()
                || !permissions.effective(origin.recipientId()).contains("query.view")
                || scope == null
                || !queries.canOpenFeedbackAnswer(origin.requestId(), origin.recipientId(), scope)) {
            throw BizIllegalException.denied();
        }
        return scope;
    }

    /**
     * 补充历史正文按对应原问读取，后台才能看到所属的原事项。
     */
    private FeedbackSupplementVO.Entry supplement(Supplement item, AccessScopeVO scope, boolean staff) {
        return new FeedbackSupplementVO.Entry(
                item.id(),
                staff ? Ids.text(item.issueId()) : null,
                visibleText(scope, item.prompt()),
                visibleText(scope, item.answer()),
                instant(item.requestedAt()),
                nullableInstant(item.answeredAt()),
                item.disposition(),
                staff ? visibleText(scope, item.dispositionReason()) : null,
                nullableInstant(item.disposedAt()));
    }

    /**
     * 在事项锁内分派主负责人，独立任务的责任和处理结果仍由各任务维护。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
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
            requireCandidate(assignee);
            var work = tasks.list(row.id(), true);
            String status = taskStatus(work);
            if (feedback.assign(row.id(), row.version(), assignee, normalized.direction(), status) != 1) {
                throw BizIllegalException.conflict();
            }
            // 单独分流到源数据时直接建任务，避免再分派一次相同责任。
            if (work.isEmpty() && SOURCE_DATA.equals(normalized.direction())) {
                tasks.insert(new Task(
                        row.id(),
                        row.version() + 1,
                        SOURCE_DATA,
                        assignee,
                        null,
                        normalized.reason(),
                        IN_PROGRESS,
                        false,
                        null,
                        null,
                        null,
                        false));
            }
            // 主负责人变化会撤销原复验，已交付的独立任务仍保留结果。
            if (!work.isEmpty()) {
                tasks.invalidate(row.id());
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
                    new ChangeVO("status", "状态", row.status(), status),
                    new ChangeVO("assigneeId", "负责人编号", Ids.text(row.assigneeId()), Ids.text(assignee)),
                    new ChangeVO("direction", "初步方向", row.direction(), normalized.direction()));
        });
    }

    /**
     * 计算仍需办理的任务是否已全部提交，全部退出时保留说明结案入口。
     */
    private String taskStatus(List<Task> work) {
        return work.stream().noneMatch(task -> IN_PROGRESS.equals(task.status()))
                        && work.stream().anyMatch(task -> SUBMITTED.equals(task.status()))
                ? TO_VERIFY
                : IN_PROGRESS;
    }

    /**
     * 添加或转派治理、技术或源数据任务，并以主事项版本防止与其他动作互相覆盖。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO assignTask(String id, Integer taskId, String command, AssignIssueDTO input) {
        long assignee = Ids.parse(input.assigneeId());
        String direction = input.direction();
        String reason = requiredText(input.reason(), "请填写任务范围或转派依据");
        if (!TASK_DIRECTIONS.contains(direction)) {
            throw BizIllegalException.invalid("请选择治理、技术或源数据任务");
        }
        var normalized = new AssignIssueDTO(input.expectedVersion(), Ids.text(assignee), direction, reason);
        String action = taskId == null ? "TASK_ADDED" : "TASK_ASSIGNED";
        return change(
                id,
                command,
                ASSIGN,
                "feedback.task.assign",
                input.expectedVersion(),
                List.of("task-assignment", taskId == null ? 0 : taskId, normalized),
                row -> {
                    // 已确定主负责人后，才能将问题拆成独立任务。
                    if (row.assigneeId() == null || !assignable(row)) {
                        throw BizIllegalException.state("请先确认主事项负责人和当前办理状态");
                    }
                    requireCandidate(assignee);
                    var work = tasks.list(row.id(), true);
                    int target;
                    if (taskId == null) {
                        // 首次拆分用初始事项版本标识原任务，带入核查和处理结果后重做复验。
                        if (work.isEmpty()
                                && TASK_DIRECTIONS.contains(row.direction())
                                && !SOURCE_DATA.equals(row.direction())) {
                            tasks.insert(new Task(
                                    row.id(),
                                    1,
                                    row.direction(),
                                    row.assigneeId(),
                                    null,
                                    null,
                                    TO_VERIFY.equals(row.status()) ? SUBMITTED : IN_PROGRESS,
                                    feedback.hasCurrentReview(row.id(), row.assigneeId()),
                                    row.resultVersion(),
                                    null,
                                    null,
                                    false));
                        }
                        target = row.version() + 1;
                        tasks.insert(new Task(
                                row.id(),
                                target,
                                direction,
                                assignee,
                                null,
                                reason,
                                IN_PROGRESS,
                                false,
                                null,
                                null,
                                null,
                                false));
                    } else {
                        var task = requireTask(work, taskId);
                        // 转派已退出的任务保留退出状态，方便更换失效负责人后再恢复。
                        if (!direction.equals(task.direction()) || assignee == task.assigneeId()) {
                            throw BizIllegalException.state("请选择同一任务下不同的负责人");
                        }
                        target = task.taskId();
                        tasks.update(
                                task, assignee, active(task) ? IN_PROGRESS : task.status(), false, null, null, false);
                    }
                    tasks.invalidate(row.id());
                    return recordTask(
                            row,
                            target,
                            action,
                            assignee,
                            direction,
                            reason,
                            null,
                            taskStatus(tasks.list(row.id(), true)));
                });
    }

    /**
     * 退出时核对剩余影响，恢复时重新核查，两个动作都保留原有处理历史。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO changeTaskStatus(String id, int taskId, String command, ChangeFeedbackTaskDTO input) {
        String status = input.status();
        boolean restore = IN_PROGRESS.equals(status);
        String reason = requiredText(input.reason(), "请填写办理依据");
        String impact = restore ? null : requiredText(input.remainingImpact(), "请说明剩余影响及安排");
        if (!restore && (!Set.of("NO_CHANGE", "CANCELLED").contains(status) || !input.remainingImpactCovered())) {
            throw BizIllegalException.invalid("请确认剩余影响均已覆盖后办理退出");
        }
        var normalized = new ChangeFeedbackTaskDTO(input.expectedVersion(), status, reason, impact, !restore);
        guard.shared();
        String permission = permissions.has(ASSIGN) ? ASSIGN : PROCESS;
        return change(
                id,
                command,
                permission,
                "feedback.task.disposition",
                input.expectedVersion(),
                List.of("task-disposition", taskId, normalized),
                row -> {
                    // 主负责人或协调者可办，其他任务负责人只能处理自己获派的工作。
                    if (!ASSIGN.equals(permission) && !Objects.equals(row.assigneeId(), UserContext.getUser())) {
                        throw BizIllegalException.denied();
                    }
                    if (!assignable(row)) {
                        throw BizIllegalException.state("当前事项不能调整处理任务");
                    }
                    var task = requireTask(tasks.list(row.id(), true), taskId);
                    if (restore == active(task)) {
                        throw BizIllegalException.state("任务状态已变化，请重新读取");
                    }
                    // 仍需外部修正的任务保留在办，确认等待已经结束后才可退出。
                    if (task.waitingExternal()) {
                        throw BizIllegalException.state("仍在等待修复生效，请先记录最新跟进结论");
                    }
                    if (restore) {
                        requireCandidate(task.assigneeId());
                    }
                    tasks.update(
                            task, task.assigneeId(), status, false, restore ? null : task.resultVersion(), null, false);
                    tasks.invalidate(row.id());
                    return recordTask(
                            row,
                            taskId,
                            restore ? "TASK_RESTORED" : "TASK_" + status,
                            task.assigneeId(),
                            task.direction(),
                            reason,
                            impact,
                            taskStatus(tasks.list(row.id(), true)));
                });
    }

    /**
     * 任务状态、事项阶段和业务活动在同一事务内推进。
     */
    private List<ChangeVO> recordTask(
            Issue row,
            int taskId,
            String action,
            long assignee,
            String direction,
            String note,
            String impact,
            String status) {
        int next = row.version() + 1;
        if (tasks.advance(row.id(), row.version(), status) != 1) {
            throw BizIllegalException.conflict();
        }
        tasks.appendActivity(
                new Activity(row.id(), next, UserContext.getUser(), action, assignee, direction, note, impact, null),
                taskId);
        return List.of(
                new ChangeVO("taskId", "任务编号", null, Integer.toString(taskId)),
                new ChangeVO("status", "状态", row.status(), status));
    }

    /**
     * 当前负责人记录主事项或自己负责的任务核查，旧版本不能覆盖新分派。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO review(String id, Integer taskId, String command, ReviewIssueDTO input) {
        var normalized = new ReviewIssueDTO(
                input.expectedVersion(),
                requiredText(input.basis(), "请填写核查依据"),
                input.rootCause() == null || input.rootCause().isBlank()
                        ? null
                        : input.rootCause().strip());
        return change(id, command, PROCESS, input.expectedVersion(), taskInput(taskId, normalized), row -> {
            long actor = UserContext.getUser();
            // 独立任务的核查由该任务负责人记录，不代替主事项核查。
            if (taskId != null) {
                var task = requireTask(tasks.list(row.id(), true), taskId);
                if (task.assigneeId() != actor
                        || !IN_PROGRESS.equals(row.status())
                        || !IN_PROGRESS.equals(task.status())) {
                    throw BizIllegalException.denied();
                }
                tasks.update(
                        task,
                        actor,
                        task.status(),
                        true,
                        task.resultVersion(),
                        task.verificationVersion(),
                        task.waitingExternal());
                return recordTask(
                        row,
                        taskId,
                        "TASK_REVIEWED",
                        actor,
                        task.direction(),
                        normalized.basis(),
                        normalized.rootCause(),
                        row.status());
            }
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

    /**
     * 当前技术负责人确认修复已生效后为对应的处理范围提交结果。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO submitResult(String id, Integer taskId, String command, SubmitIssueResultDTO input) {
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
        return change(
                id,
                command,
                PROCESS,
                "feedback.result.submit",
                input.expectedVersion(),
                taskInput(taskId, normalized),
                row -> {
                    long actor = UserContext.getUser();
                    var task = requireTechnicalTask(row, taskId, actor);
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
                            null,
                            null,
                            null));
                    return acceptResult(row, task, next, actor);
                });
    }

    /**
     * 治理负责人为当前处理范围提交已核实生效的知识版本及覆盖依据。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO submitKnowledge(String id, Integer taskId, String command, SubmitKnowledgeResultDTO input) {
        long binding = Ids.parse(input.bindingId());
        var normalized = new SubmitKnowledgeResultDTO(
                input.expectedVersion(),
                Ids.text(binding),
                requiredText(input.summary(), "请填写补齐的知识"),
                requiredText(input.checkMethod(), "请填写原问题的覆盖依据"));
        return change(
                id,
                command,
                PROCESS,
                "feedback.knowledge.submit",
                input.expectedVersion(),
                taskInput(taskId, normalized),
                row -> {
                    long actor = UserContext.getUser();
                    var task = resultTask(row, taskId, true);
                    if (!canSubmitKnowledge(row, task, actor)) {
                        throw BizIllegalException.denied();
                    }
                    publishedBinding(row.sourceId(), binding, true);
                    if (lifecycle.usedBinding(row.id(), binding)) {
                        throw BizIllegalException.state("该发布仍是关联原问题所质疑的版本");
                    }
                    int next = row.version() + 1;
                    resolutions.appendResult(new ResultRecord(
                            row.id(),
                            next,
                            actor,
                            "KNOWLEDGE",
                            normalized.summary(),
                            normalized.checkMethod(),
                            null,
                            null,
                            null,
                            binding,
                            null));
                    return acceptResult(row, task, next, actor);
                });
    }

    /**
     * 保存实际跟进。若当期修正重新进入跟进，则撤回当前结果和复验引用。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO followUpSourceData(String id, int taskId, String command, FeedbackProgressDTO input) {
        return followUp(id, taskId, command, input, SOURCE_DATA);
    }

    /**
     * 技术修复可保持在办，也可从待验证撤回到跟进。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO followUpTechnical(String id, Integer taskId, String command, FeedbackProgressDTO input) {
        return followUp(id, taskId, command, input, "TECHNICAL");
    }

    /**
     * 两类修正的跟进使用相同的撤回、等候和历史规则。
     */
    private MutationVO followUp(
            String id, Integer taskId, String command, FeedbackProgressDTO input, String direction) {
        boolean source = SOURCE_DATA.equals(direction);
        String kind = source ? "source" : "technical";
        var normalized = new FeedbackProgressDTO(
                input.expectedVersion(),
                input.waitingExternal(),
                requiredText(input.workDone(), "请填写已做工作"),
                requiredText(input.nextStep(), "请填写下一步安排"));
        return change(
                id,
                command,
                PROCESS,
                "feedback." + kind + ".followup",
                input.expectedVersion(),
                List.of(kind + "-followup", taskId == null ? 0 : taskId, normalized),
                row -> {
                    long actor = UserContext.getUser();
                    var task =
                            source ? requireSourceTask(row, taskId, actor) : requireTechnicalTask(row, taskId, actor);
                    int target = task == null ? 1 : task.taskId();
                    if (task == null) {
                        // 单技术事项沿用已有核查和负责人，首次跟进后用同一任务状态继续办理。
                        tasks.insert(new Task(
                                row.id(),
                                target,
                                direction,
                                actor,
                                null,
                                null,
                                IN_PROGRESS,
                                true,
                                null,
                                null,
                                null,
                                normalized.waitingExternal()));
                    } else {
                        // 撤回当前结果和复验引用，原修正与验收仍可在历史中回看。
                        tasks.update(task, actor, IN_PROGRESS, true, null, null, normalized.waitingExternal());
                    }
                    tasks.invalidate(row.id());
                    return recordTask(
                            row,
                            target,
                            (source ? "SOURCE_" : "TECHNICAL_")
                                    + (normalized.waitingExternal() ? "WAITING" : "FOLLOW_UP"),
                            actor,
                            direction,
                            normalized.workDone(),
                            normalized.nextStep(),
                            IN_PROGRESS);
                });
    }

    /**
     * 保存源系统实际修正与生效事实，此操作只写入平台处理台账。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO submitSourceData(String id, int taskId, String command, SourceDataResultDTO input) {
        if (!input.effectiveConfirmed()) {
            // 未同步生效仍按跟进保存，不推进到验证阶段。
            throw BizIllegalException.invalid("请确认源系统修正及必要同步已经生效");
        }
        var normalized = new SourceDataResultDTO(
                input.expectedVersion(),
                requiredText(input.repairer(), "请填写实际修正人或团队"),
                requiredText(input.summary(), "请填写修正的数据范围与内容"),
                requiredText(input.checkMethod(), "请填写只读复查方式与实际结果"),
                optionalText(input.reference()),
                true);
        return change(
                id,
                command,
                PROCESS,
                "feedback.source.result",
                input.expectedVersion(),
                List.of("source-result", taskId, normalized),
                row -> {
                    // 记录人来自当前会话，实际修正人是独立的业务事实。
                    long actor = UserContext.getUser();
                    var task = requireSourceTask(row, taskId, actor);
                    int next = row.version() + 1;
                    resolutions.appendResult(new ResultRecord(
                            row.id(),
                            next,
                            actor,
                            SOURCE_DATA,
                            normalized.summary(),
                            normalized.checkMethod(),
                            normalized.reference(),
                            null,
                            null,
                            null,
                            normalized.repairer()));
                    return acceptResult(row, task, next, actor);
                });
    }

    /**
     * 跟进和提交源数据结果由当前已核查的任务负责人办理。
     */
    private Task requireSourceTask(Issue row, int taskId, long actor) {
        var task = requireTask(tasks.list(row.id(), true), taskId);
        if (!SOURCE_DATA.equals(task.direction()) || !canWork(row, task, actor) || !task.reviewed()) {
            throw BizIllegalException.denied();
        }
        return task;
    }

    /**
     * 技术结果与跟进都由已核查的当前责任人保存，待验证期间仍可修订。
     */
    private Task requireTechnicalTask(Issue row, Integer taskId, long actor) {
        var task = resultTask(row, taskId, true);
        if (!(task == null ? ownsOpenWork(row, actor) : canWork(row, task, actor))) {
            // 转派后旧责任人无权继续写入。
            throw BizIllegalException.denied();
        }
        String direction = task == null ? row.direction() : task.direction();
        boolean reviewed = task == null ? feedback.hasCurrentReview(row.id(), actor) : task.reviewed();
        if (!"TECHNICAL".equals(direction) || !reviewed) {
            // 结果与跟进使用同一项当期核查作为入口。
            throw BizIllegalException.state("请先完成当前技术事项的核查记录");
        }
        return task;
    }

    /**
     * 单技术事项在办理或待验证时都允许当前负责人修订。
     */
    private boolean ownsOpenWork(Issue row, long actor) {
        return (IN_PROGRESS.equals(row.status()) || TO_VERIFY.equals(row.status()))
                && Objects.equals(row.assigneeId(), actor);
    }

    /**
     * 两类办理结果使用同一版本和审计规则，主事项阶段由当期任务结果决定。
     */
    private List<ChangeVO> acceptResult(Issue row, Task task, int next, long actor) {
        // 新版本改变复验范围，其他任务已登记的复验也要重新确认。
        if (task != null) {
            tasks.update(task, actor, SUBMITTED, true, next, null, false);
            tasks.invalidate(row.id());
            return recordTask(
                    row,
                    task.taskId(),
                    "RESULT_SUBMITTED",
                    actor,
                    task.direction(),
                    "已提交处理结果",
                    null,
                    taskStatus(tasks.list(row.id(), true)));
        }
        if (resolutions.submit(row.id(), row.version(), next) != 1) {
            throw BizIllegalException.conflict();
        }
        feedback.appendActivity(
                new Activity(row.id(), next, actor, "RESULT_SUBMITTED", null, null, "已提交处理结果", null, null));
        return List.of(new ChangeVO("status", "状态", row.status(), TO_VERIFY));
    }

    /**
     * 针对当前治理结果用原问题发起实际问数，复验人可在工作台查看结果。
     */
    @Transactional
    public Accepted replayQuestion(String id, Integer taskId, String command) {
        guard.shared();
        permissions.require(VIEW);
        permissions.require(VERIFY);
        var row = requireIssue(feedback.issue(Ids.parse(id)));
        var task = resultTask(row, taskId, false);
        var result = currentResult(row, task);
        if (row.pendingSupplement()
                || !TO_VERIFY.equals(row.status())
                || result == null
                || !supportsReplay(result)
                || !eligibleHandler(row, task)
                || validVerification(row, task, currentVerification(row, task), false)) {
            throw BizIllegalException.state("当前处理结果不处于可问数复验状态");
        }
        var scope = replayScope(UserContext.getUser(), row.sourceId());
        if (scope == null) {
            throw BizIllegalException.denied();
        }
        // 只有治理修正需要先确认提交时关联的发布仍然生效。
        if (result.knowledgeBindingId() != null) {
            publishedBinding(row.sourceId(), result.knowledgeBindingId(), false);
        }
        return queries.replayFeedback(row.requestId(), scope, command);
    }

    /**
     * 人工复验绑定当期处理结果和操作者的源权限，失败退回对应任务。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO verify(String id, Integer taskId, String command, VerifyIssueDTO input) {
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
                requiredText(input.dataBasis(), "请填写数据与复验依据"),
                optionalText(input.recheckRequestId()));
        return change(id, command, VERIFY, input.expectedVersion(), taskInput(taskId, normalized), row -> {
            requireCompleteEvidence(row);
            var task = resultTask(row, taskId, true);
            var result = currentResult(row, task);
            if (!TO_VERIFY.equals(row.status())
                    || result == null
                    || !eligibleHandler(row, task)
                    || (task != null && !SUBMITTED.equals(task.status()))) {
                throw BizIllegalException.state("当前处理结果或负责人资格已变化，请重新核查");
            }
            long verifier = UserContext.getUser();
            var context = verificationContext(verifier, row.sourceId());
            if (context == null) {
                throw BizIllegalException.denied();
            }
            boolean passed = "PASSED".equals(decision);
            if (result.knowledgeBindingId() != null
                    || (SOURCE_DATA.equals(result.impact()) && normalized.recheckRequestId() != null)) {
                if (!matchesRecheck(row, task, result, verifier, normalized.recheckRequestId(), passed, true)) {
                    throw BizIllegalException.state("请核对当前修正范围、实际问数轮次和数据依据");
                }
            } else if (normalized.recheckRequestId() != null) {
                throw BizIllegalException.invalid("技术复验使用实际复查记录");
            }
            String nextStatus = passed ? TO_VERIFY : IN_PROGRESS;
            int next = row.version() + 1;
            resolutions.appendVerification(new Verification(
                    row.id(),
                    next,
                    result.version(),
                    verifier,
                    context,
                    decision,
                    normalized.testScope(),
                    normalized.expected(),
                    normalized.actual(),
                    normalized.dataBasis(),
                    null,
                    normalized.recheckRequestId()));
            // 复验不通过仅打回对应任务，事项等待该任务补正。
            if (task != null) {
                tasks.update(
                        task,
                        task.assigneeId(),
                        passed ? SUBMITTED : IN_PROGRESS,
                        task.reviewed(),
                        result.version(),
                        next,
                        false);
                return recordTask(
                        row,
                        task.taskId(),
                        passed ? "VERIFIED" : "RECHECK_FAILED",
                        task.assigneeId(),
                        task.direction(),
                        "已登记复验",
                        null,
                        nextStatus);
            }
            if (resolutions.verify(row.id(), row.version(), next, nextStatus) != 1) {
                throw BizIllegalException.conflict();
            }
            feedback.appendActivity(new Activity(
                    row.id(), next, verifier, passed ? "VERIFIED" : "RECHECK_FAILED", null, null, "已登记复验", null, null));
            return List.of(
                    new ChangeVO("verification", "复验结论", null, passed ? "通过" : "未通过"),
                    new ChangeVO("status", "状态", row.status(), nextStatus));
        });
    }

    /**
     * 保持原单任务命令的防重指纹，新增任务命令同时固定目标任务。
     */
    private Object taskInput(Integer taskId, Object input) {
        return taskId == null ? input : List.of(taskId, input);
    }

    /**
     * 事项拆分后结果动作必须指明任务，同时阻止借用其他事项的任务编号。
     */
    private Task resultTask(Issue row, Integer taskId, boolean lock) {
        var work = tasks.list(row.id(), lock);
        if (taskId != null) {
            return requireTask(work, taskId);
        }
        if (!work.isEmpty()) {
            throw BizIllegalException.state("请选择反馈处理任务");
        }
        return null;
    }

    /**
     * 任务编号在当前事项内解析。
     */
    private Task requireTask(List<Task> work, int taskId) {
        return work.stream()
                .filter(task -> task.taskId() == taskId)
                .findFirst()
                .orElseThrow(BizIllegalException::missing);
    }

    /**
     * 在办和已提交的任务都属于此次办理范围。
     */
    private boolean active(Task task) {
        return IN_PROGRESS.equals(task.status()) || SUBMITTED.equals(task.status());
    }

    /**
     * 只有当前任务负责人可以在事项办理期间保存结果修订。
     */
    private boolean canWork(Issue row, Task task, long actor) {
        return (IN_PROGRESS.equals(row.status()) || TO_VERIFY.equals(row.status()))
                && active(task)
                && actor == task.assigneeId();
    }

    /** 只读预览确认当前状态、原提问人和将提交的受控回告。 */
    @Transactional
    public FeedbackNoticeService.Preview closurePreview(String id, String outcome, String reason) {
        guard.shared();
        permissions.require(CLOSE);
        var row = requireIssue(feedback.issue(Ids.parse(id)));
        return notices.preview(row, closureTemplate(row, outcome, reason, false));
    }

    /**
     * 结案和回告接收一同落盘，写事务在锁内核对当期复验。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO close(String id, String command, CloseIssueDTO input) {
        var normalized = new CloseIssueDTO(
                input.expectedVersion(),
                input.outcome(),
                optionalText(input.reason()),
                requiredText(input.conclusion(), "请填写结案结论"),
                requiredText(input.basis(), "请填写结案依据"),
                input.recipientId(),
                input.confirmedTemplate(),
                optionalText(input.confirmedRecipients()));
        return change(id, command, CLOSE, input.expectedVersion(), normalized, row -> {
            String template = closureTemplate(row, normalized.outcome(), normalized.reason(), true);
            var preview = notices.preview(row, template);
            if (!preview.recipientId().equals(normalized.recipientId())
                    || !preview.template().equals(normalized.confirmedTemplate())
                    || !Objects.equals(preview.confirmation(), normalized.confirmedRecipients())) {
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
            notices.enqueue(row, template, command, row.version() + 1);
            return List.of(new ChangeVO("status", "状态", row.status(), normalized.outcome()));
        });
    }

    /**
     * 处理能力按当前任务投影，主事项统一提供结案能力和回告状态。
     */
    private FeedbackIssueDetailVO.Resolution resolution(
            Issue row, Task task, long actor, AccessScopeVO scope, List<Task> work) {
        boolean separate = task == null && !work.isEmpty();
        var result = separate ? null : currentResult(row, task);
        var verification = separate ? null : currentVerification(row, task);
        boolean valid = validVerification(row, task, verification, false);
        boolean canClose = task == null && permissions.has(CLOSE);
        String direction = task == null ? row.direction() : task.direction();
        boolean canSubmit = !separate
                && (task == null ? ownsOpenWork(row, actor) : canWork(row, task, actor))
                && "TECHNICAL".equals(direction)
                && permissions.has(PROCESS)
                && (task == null ? feedback.hasCurrentReview(row.id(), actor) : task.reviewed());
        boolean canVerify = canVerifyResult(row, task, result, valid, actor);
        boolean canReplay = canVerify
                && scope != null
                && result != null
                && supportsReplay(result)
                && replayScope(actor, row.sourceId()) != null
                && queries.canReplayFeedback(row.requestId(), scope);
        var summary = result == null
                ? null
                : new FeedbackIssueDetailVO.ProcessingResult(
                        result.version(),
                        result.impact(),
                        visibleText(scope, result.summary()),
                        visibleText(scope, result.checkMethod()),
                        visibleText(scope, result.referenceText()),
                        visibleText(scope, result.regression()),
                        instant(result.submittedAt()),
                        scope == null || result.knowledgeBindingId() == null
                                ? null
                                : Ids.text(result.knowledgeBindingId()),
                        visibleText(scope, result.sourceRepairer()));
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
                        instant(verification.verifiedAt()),
                        scope != null && verification.verifierId() == actor ? verification.recheckRequestId() : null,
                        verification.recheckRequestId() != null);
        var notice = task == null && (RESOLVED.equals(row.status()) || CLOSED_OTHER.equals(row.status()))
                ? notices.status(row.id())
                : null;
        var delivery = notice == null
                ? null
                : new FeedbackIssueDetailVO.NoticeStatus(
                        notice.state(), nullableInstant(notice.sentAt()), nullableInstant(notice.readAt()));
        return new FeedbackIssueDetailVO.Resolution(
                canSubmit,
                canVerify,
                canClose
                        && !row.pendingSupplement()
                        && TO_VERIFY.equals(row.status())
                        && (separate ? verifiedTasks(row, work, false) : valid),
                canClose && canExplainClose(row, work),
                summary,
                checked,
                delivery,
                !separate && scope != null && canSubmitKnowledge(row, task, actor),
                canReplay,
                canReplay ? queries.latestFeedbackReplay(row.requestId(), replayAfter(task, result)) : null,
                task != null
                        && SOURCE_DATA.equals(task.direction())
                        && canWork(row, task, actor)
                        && task.reviewed()
                        && permissions.has(PROCESS));
    }

    /**
     * 展示单个任务的责任、当前结果及协调操作，正文仍受原问证据授权约束。
     */
    private FeedbackTaskVO taskDetail(Issue row, Task task, long actor, AccessScopeVO scope) {
        boolean canAssign = assignable(row) && permissions.has(ASSIGN);
        return new FeedbackTaskVO(
                task.taskId(),
                task.direction(),
                Ids.text(task.assigneeId()),
                task.assigneeName(),
                visibleText(scope, task.scopeText()),
                task.status(),
                canWork(row, task, actor) && IN_PROGRESS.equals(task.status()) && permissions.has(PROCESS),
                canAssign,
                !task.waitingExternal()
                        && (canAssign
                                || (assignable(row)
                                        && Objects.equals(row.assigneeId(), actor)
                                        && permissions.has(PROCESS))),
                task.waitingExternal(),
                resolution(row, task, actor, scope, List.of()));
    }

    /**
     * 治理负责人须具备已发布版本和原问的核查资格。
     */
    private boolean canSubmitKnowledge(Issue row, Task task, long actor) {
        return (task == null ? owner(row, actor) : canWork(row, task, actor))
                && "KNOWLEDGE".equals(task == null ? row.direction() : task.direction())
                && permissions.has(PROCESS)
                && permissions.has("releases.view")
                && permissions.has("feedback.evidence")
                && permissions.has("query.view")
                && (task == null ? feedback.hasCurrentReview(row.id(), actor) : task.reviewed())
                && access.findSource(actor, row.sourceId())
                        .map(scope -> queries.feedbackQuestion(row.requestId(), scope) != null)
                        .orElse(false);
    }

    /** 重跑原问与读取其依据分别要求当前范围和对应操作权限。 */
    private AccessScopeVO replayScope(long actor, long source) {
        var codes = permissions.effective(actor);
        return codes.containsAll(Set.of("feedback.evidence", "query.view", "query.ask"))
                ? access.findSource(actor, source).orElse(null)
                : null;
    }

    /** 发布、暂停及撤下以当前锁内的权威版本为准。 */
    private Binding publishedBinding(long source, long id, boolean lock) {
        var origin = catalog.source(source, lock);
        var binding = versions.current(source, id);
        if (versions.isPaused(binding.scopeId())) {
            throw BizIllegalException.state("关联的知识版本已暂停使用");
        }
        versions.requireBasis(origin, binding);
        return binding;
    }

    /**
     * 治理复验的实际问数须晚于处理范围变动，并采用对应的现行绑定。
     */
    private boolean matchesRecheck(
            Issue row, Task task, ResultRecord result, long verifier, String request, boolean passed, boolean lock) {
        var scope = replayScope(verifier, row.sourceId());
        if (request == null || scope == null || !queries.canReplayFeedback(row.requestId(), scope)) {
            return false;
        }
        try {
            // 源数据修正可沿用原发布，按当前可用发布和实际只读查询结果核对。
            if (SOURCE_DATA.equals(result.impact())) {
                if (lock) {
                    catalog.source(row.sourceId(), true);
                }
                return queries.sourceFeedbackReplayMatches(
                        request,
                        row.requestId(),
                        verifier,
                        row.sourceId(),
                        replayAfter(task, result),
                        queries.feedbackRuleSignature(scope),
                        passed);
            }
            var binding = publishedBinding(row.sourceId(), result.knowledgeBindingId(), lock);
            if (lifecycle.usedBinding(row.id(), binding.id())) {
                return false;
            }
            var after = replayAfter(task, result);
            var earliest = binding.verifiedAt().isAfter(after) ? binding.verifiedAt() : after;
            return queries.feedbackReplayMatches(
                    request,
                    row.requestId(),
                    verifier,
                    row.sourceId(),
                    binding.id(),
                    earliest,
                    queries.feedbackRuleSignature(scope),
                    passed);
        } catch (BizIllegalException changed) {
            return false;
        }
    }

    /**
     * 知识和源数据任务可使用原问题只读复验。
     */
    private boolean supportsReplay(ResultRecord result) {
        return result.knowledgeBindingId() != null || SOURCE_DATA.equals(result.impact());
    }

    /**
     * 任务范围变化后，知识复验必须引用新范围确定后实际启动的轮次。
     */
    private LocalDateTime replayAfter(Task task, ResultRecord result) {
        return task != null && task.recheckAfter().isAfter(result.submittedAt())
                ? task.recheckAfter()
                : result.submittedAt();
    }

    /**
     * 读取事项或任务当前引用的结果版本。
     */
    private ResultRecord currentResult(Issue row, Task task) {
        Integer version = task == null ? row.resultVersion() : task.resultVersion();
        return version == null ? null : resolutions.result(row.id(), version);
    }

    /**
     * 结案时在来源锁内重新核对当期处理结果、发布绑定及复验人的授权。
     */
    private boolean validVerification(Issue row, Task task, Verification record, boolean lock) {
        boolean accepted = record != null
                && "PASSED".equals(record.decision())
                && Objects.equals(task == null ? row.resultVersion() : task.resultVersion(), record.resultVersion())
                && eligibleHandler(row, task)
                && record.contextSignature().equals(verificationContext(record.verifierId(), row.sourceId()));
        if (!accepted) {
            return false;
        }
        var result = currentResult(row, task);
        return result != null
                && ((result.knowledgeBindingId() == null && record.recheckRequestId() == null)
                        || matchesRecheck(
                                row, task, result, record.verifierId(), record.recheckRequestId(), true, lock));
    }

    /**
     * 至少一项任务实际修复且所有必要修复已复验，才可按已解决结案。
     */
    private boolean verifiedTasks(Issue row, List<Task> work, boolean lock) {
        var active = work.stream().filter(this::active).toList();
        return !active.isEmpty()
                && eligibleHandler(row, null)
                && active.stream()
                        .allMatch(task -> SUBMITTED.equals(task.status())
                                && validVerification(row, task, currentVerification(row, task), lock));
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

    /**
     * 处理人员停用或权限调整后，需要重新分派并核对当期结果。
     */
    private boolean eligibleHandler(Issue row, Task task) {
        Long assignee = task == null ? row.assigneeId() : Long.valueOf(task.assigneeId());
        return assignee != null && eligibleAssignee(assignee);
    }

    /**
     * 任务与事项使用同一套当前人员资格。
     */
    private boolean eligibleAssignee(long assignee) {
        var user = users.selectById(assignee);
        return user != null
                && "ENABLED".equals(user.getLifecycle())
                && !user.isMustChangePassword()
                && permissions.effective(assignee).containsAll(Set.of(VIEW, PROCESS));
    }

    /**
     * 在写入分派前要求候选仍具备处理资格。
     */
    private void requireCandidate(long assignee) {
        if (!eligibleAssignee(assignee)) {
            throw BizIllegalException.state("所选人员当前不能办理反馈事项");
        }
    }

    /**
     * 按事项或任务引用的确切版本查人工复验。
     */
    private Verification currentVerification(Issue row, Task task) {
        Integer version = task == null ? row.verificationVersion() : task.verificationVersion();
        return version == null ? null : resolutions.verification(row.id(), version);
    }

    /**
     * 没有必要任务仍在办且主负责人具备本期核查时，允许说明结案。
     */
    private boolean canExplainClose(Issue row, List<Task> work) {
        return IN_PROGRESS.equals(row.status())
                && !row.pendingSupplement()
                && work.stream().noneMatch(this::active)
                && row.assigneeId() != null
                && feedback.hasCurrentReview(row.id(), row.assigneeId());
    }

    /**
     * 已解决和说明结案各有事实条件，仅返回条件满足后的固定回告模板。
     */
    private String closureTemplate(Issue row, String outcome, String reason, boolean lock) {
        requireCompleteEvidence(row);
        var work = tasks.list(row.id(), lock);
        if (RESOLVED.equals(outcome)) {
            boolean verified = work.isEmpty()
                    ? validVerification(row, null, currentVerification(row, null), lock)
                    : verifiedTasks(row, work, lock);
            if (!TO_VERIFY.equals(row.status()) || !verified) {
                throw BizIllegalException.state("当前处理结果尚未通过有效复验");
            }
            return RESOLVED;
        }
        if (CLOSED_OTHER.equals(outcome)
                && reason != null
                && OTHER_REASONS.contains(reason)
                && canExplainClose(row, work)) {
            return reason;
        }
        throw BizIllegalException.state("请核对当前核查记录和其他结案原因");
    }

    /**
     * 必要补充未齐时，保留已有任务结果，只阻止登记复验和结案。
     */
    private void requireCompleteEvidence(Issue row) {
        if (row.pendingSupplement()) {
            throw BizIllegalException.state("请等待必要的用户补充后再复验或结案");
        }
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
        return change(id, command, action, auditAction, version, input, null, (row, ignored) -> apply.apply(row));
    }

    /**
     * 先按编号锁定涉及的事项，再锁定操作者，避免事项更新的人员外键检查与其他办理命令形成死锁。
     */
    private MutationVO change(
            String id,
            String command,
            String action,
            String auditAction,
            int version,
            Object input,
            Long peerId,
            BiFunction<Issue, Issue, List<ChangeVO>> apply) {
        guard.shared();
        permissions.require(action);
        var key = commandId(command);
        long actor = UserContext.getUser();
        long issueId = Ids.parse(id);
        Issue peer = peerId != null && peerId < issueId ? requireIssue(feedback.lockIssue(peerId)) : null;
        var row = requireIssue(feedback.lockIssue(issueId));
        if (peerId != null && peer == null) {
            peer = requireIssue(feedback.lockIssue(peerId));
        }
        users.lock(actor);
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
        var changes = apply.apply(row, peer);
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
        String status = displayStatus(row);
        return new IssueRow(
                Ids.text(row.id()),
                Ids.text(row.sourceId()),
                row.sourceName(),
                row.origin(),
                status,
                Ids.text(row.assigneeId()),
                row.assigneeName(),
                instant(row.createdAt()),
                instant(row.updatedAt()),
                row.summary(),
                row.referenceCount(),
                switch (status) {
                    case PENDING -> "分派负责人";
                    case WAITING -> "等待用户补充";
                    case IN_PROGRESS -> "核查与处理";
                    case TO_VERIFY -> row.verificationVersion() == null ? "复验处理结果" : "核对复验与结案";
                    case MERGED -> "查看主事项";
                    default -> "查看结果";
                });
    }

    /**
     * 等待用户补充始终优先于任务的当前办理阶段。
     */
    private String displayStatus(Issue row) {
        return row.pendingSupplement() ? WAITING : row.status();
    }

    /**
     * 事项和任务的历史基础字段可见，正文统一检查证据权限。
     */
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
                instant(row.occurredAt()),
                row.taskId(),
                Ids.text(row.relatedIssueId()),
                row.completionRound(),
                row.closureCode());
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
