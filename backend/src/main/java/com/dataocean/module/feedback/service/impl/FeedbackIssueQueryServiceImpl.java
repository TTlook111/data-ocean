package com.dataocean.module.feedback.service.impl;

import static com.dataocean.common.Times.instant;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.ASSIGN;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.CLOSE;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.CLOSED_OTHER;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.IN_PROGRESS;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.MERGED;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.PENDING;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.PROCESS;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.RESOLVED;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.SOURCE_DATA;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.TO_VERIFY;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.VERIFY;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.VIEW;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.WAITING;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.assignable;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.canWork;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.displayStatus;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.owner;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.ownsOpenWork;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.requireIssue;

import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.PageVO;
import com.dataocean.common.TextSearch;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.access.entity.vo.AccessScopeVO;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.feedback.entity.FeedbackProtocol.IssueRow;
import com.dataocean.module.feedback.entity.query.FeedbackIssueQuery;
import com.dataocean.module.feedback.entity.vo.FeedbackIssueDetailVO;
import com.dataocean.module.feedback.entity.vo.FeedbackIssueDetailVO.Report;
import com.dataocean.module.feedback.entity.vo.FeedbackTaskVO;
import com.dataocean.module.feedback.mapper.FeedbackMapper;
import com.dataocean.module.feedback.mapper.FeedbackMapper.ActivityRow;
import com.dataocean.module.feedback.mapper.FeedbackMapper.Issue;
import com.dataocean.module.feedback.mapper.FeedbackQueryIssueMapper;
import com.dataocean.module.feedback.mapper.FeedbackTaskMapper;
import com.dataocean.module.feedback.mapper.FeedbackTaskMapper.Progress;
import com.dataocean.module.feedback.mapper.FeedbackTaskMapper.Task;
import com.dataocean.module.feedback.service.FeedbackIssueQueryService;
import com.dataocean.module.feedback.service.FeedbackNoticeService;
import com.dataocean.module.feedback.service.FeedbackQueryIssueIntake;
import com.dataocean.module.iam.entity.vo.UserOptionVO;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.query.entity.vo.QuerySourceOptionVO;
import com.dataocean.module.query.service.QueryService;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 在当前访问范围内查询队列和事项办理投影。
 */
@Service
@RequiredArgsConstructor
public class FeedbackIssueQueryServiceImpl implements FeedbackIssueQueryService {

    /**
     * 反馈来源编码包含主动点踩与两类系统自动线索。
     */
    private static final Set<String> ORIGINS =
            Set.of("USER_DISLIKE", FeedbackQueryIssueIntake.KNOWLEDGE_GAP, FeedbackQueryIssueIntake.TECHNICAL_FAILURE);

    /**
     * 事项队列与操作台账。
     */
    private final FeedbackMapper feedback;

    /**
     * 问数自动入队线索。
     */
    private final FeedbackQueryIssueMapper queryIssues;

    /**
     * 与权限、人员状态及授权变化协调。
     */
    private final GuardMapper guard;

    /**
     * 查询当前办理及证据权限。
     */
    private final PermissionService permissions;

    /**
     * 读取当前来源和输出权限。
     */
    private final AccessScopeService access;

    /**
     * 读取按权裁剪的原问题及实际复验事实。
     */
    private final QueryService queries;

    /**
     * 读取和交付站内回告。
     */
    private final FeedbackNoticeService notices;

    /**
     * 维护事项下独立任务及当前结果引用。
     */
    private final FeedbackTaskMapper tasks;

    /**
     * 共用复验、处理人资格和结案条件。
     */
    private final FeedbackVerificationPolicy verification;

    /**
     * 按当前证据权限组装补充与主从关系。
     */
    private final FeedbackIssueLifecycleServiceImpl lifecycleService;

    /**
     * 待验证事项先按当前复验资格筛选，列表和计数共享同一读快照。
     */
    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public PageVO<IssueRow> issues(FeedbackIssueQuery query) {
        guard.shared();
        permissions.require(VIEW);
        String scope = query.getScope() == null || query.getScope().isEmpty() ? "ALL" : query.getScope();
        String origin = query.getOrigin();
        String status = query.getStatus();
        if (!Set.of("ALL", "MINE", "OWNED", "CLOSED", "MERGE").contains(scope)) {
            // 内部候选查询不作为外部队列分组。
            throw BizIllegalException.invalid("无效的事项分组");
        }
        if ("MERGE".equals(scope)) {
            // 合并候选只供协调者在同一来源内核对。
            permissions.require(ASSIGN);
            if (query.getSourceId() == null) {
                // 同源比较必须明确数据源。
                throw BizIllegalException.invalid("请选择要核对的同源事项");
            }
        }
        if (origin != null && !ORIGINS.contains(origin)) {
            // 拒绝未知来源，避免筛选错误被误解成空结果。
            throw BizIllegalException.invalid("无效的事项来源");
        }
        if (status != null
                && !Set.of(PENDING, IN_PROGRESS, WAITING, TO_VERIFY, RESOLVED, CLOSED_OTHER, MERGED)
                        .contains(status)) {
            // 只接受队列实际展示的状态编码。
            throw BizIllegalException.invalid("无效的事项状态");
        }
        long actor = UserContext.getUser();
        boolean mine = "MINE".equals(scope);
        boolean canClose = permissions.has(CLOSE);
        Long sourceId = Ids.nullable(query.getSourceId());
        Long issueId = Ids.nullable(query.getIssueId());
        String keywordPattern = keywordPattern(query.getKeyword());
        String assigneePattern = keywordPattern(query.getAssignee());
        var verifySources = mine && permissions.has(VERIFY) && permissions.has("query.view")
                ? access.current(actor).stream()
                        .map(item -> Ids.parse(item.getSource().getId()))
                        .toList()
                : List.<Long>of();
        List<Long> verificationTodoIds = List.of();
        if (mine && (status == null || TO_VERIFY.equals(status)) && (canClose || !verifySources.isEmpty())) {
            // 只读取符合当前筛选的待验证候选，在主分页前核对实时的授权和发布状态。
            var candidates = feedback.verificationCandidates(
                    sourceId, issueId, origin, keywordPattern, status, assigneePattern, verifySources, canClose);
            verificationTodoIds = candidates.stream()
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
            boolean valid =
                    verification.validVerification(row, null, verification.currentVerification(row, null), false);
            return (canClose && valid)
                    || verification.canVerifyResult(row, null, verification.currentResult(row, null), valid, actor);
        }
        return (canClose && verification.verifiedTasks(row, work, false))
                || work.stream()
                        // 只要一项必要结果可以重新复验，复验人的待办就应恢复。
                        .anyMatch(task -> verification.canVerifyResult(
                                row,
                                task,
                                verification.currentResult(row, task),
                                verification.validVerification(
                                        row, task, verification.currentVerification(row, task), false),
                                actor));
    }

    /**
     * 数据源筛选遵从与列表相同的查看权限。
     */
    @Override
    @Transactional
    public List<QuerySourceOptionVO> sources() {
        guard.shared();
        permissions.require(VIEW);
        return feedback.issueSources();
    }

    /**
     * 分派候选只返回启用且同时具有反馈查看、处理权限的人员。
     */
    @Override
    @Transactional
    public PageVO<UserOptionVO> candidates(PageQuery query, String keyword) {
        guard.shared();
        permissions.require(ASSIGN);
        String pattern = keywordPattern(keyword);
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
    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
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
        var progress = work.isEmpty()
                ? Map.<Integer, Progress>of()
                : tasks.currentProgress(row.id(), evidenceAllowed).stream()
                        .collect(Collectors.toMap(Progress::taskId, Function.identity()));
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
                        .map(task -> taskDetail(row, task, progress.get(task.taskId()), actor, visibleScope))
                        .toList(),
                lifecycleService.lifecycleDetail(row, actor, scope, work));
    }

    /**
     * 基础信息在列表和详情保持一致。
     */
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

    /**
     * 非获准业务正文不参与详情拼装。
     */
    private String visibleText(AccessScopeVO scope, String text) {
        return scope == null ? null : queries.feedbackText(scope, text);
    }

    /**
     * 处理能力按当前任务投影，主事项统一提供结案能力和回告状态。
     */
    private FeedbackIssueDetailVO.Resolution resolution(
            Issue row, Task task, long actor, AccessScopeVO scope, List<Task> work) {
        boolean separate = task == null && !work.isEmpty();
        var result = separate ? null : verification.currentResult(row, task);
        var record = separate ? null : verification.currentVerification(row, task);
        boolean valid = verification.validVerification(row, task, record, false);
        boolean canClose = task == null && permissions.has(CLOSE);
        String direction = task == null ? row.direction() : task.direction();
        boolean canSubmit = !separate
                && (task == null ? ownsOpenWork(row, actor) : canWork(row, task, actor))
                && "TECHNICAL".equals(direction)
                && permissions.has(PROCESS)
                && (task == null ? feedback.hasCurrentReview(row.id(), actor) : task.reviewed());
        boolean canVerify = verification.canVerifyResult(row, task, result, valid, actor);
        boolean canReplay = canVerify
                && scope != null
                && result != null
                && verification.supportsReplay(result)
                && verification.replayScope(actor, row.sourceId()) != null
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
        var checked = record == null
                ? null
                : new FeedbackIssueDetailVO.Recheck(
                        record.version(),
                        record.decision(),
                        valid,
                        visibleText(scope, record.testScope()),
                        visibleText(scope, record.expected()),
                        visibleText(scope, record.actual()),
                        visibleText(scope, record.dataBasis()),
                        instant(record.verifiedAt()),
                        scope != null && record.verifierId() == actor ? record.recheckRequestId() : null,
                        record.recheckRequestId() != null);
        var notice = task == null && (RESOLVED.equals(row.status()) || CLOSED_OTHER.equals(row.status()))
                ? notices.status(row.id())
                : null;
        var delivery = notice == null
                ? null
                : new FeedbackIssueDetailVO.NoticeStatus(
                        notice.state(), instant(notice.sentAt()), instant(notice.readAt()));
        return new FeedbackIssueDetailVO.Resolution(
                canSubmit,
                canVerify,
                canClose
                        && !row.pendingSupplement()
                        && TO_VERIFY.equals(row.status())
                        && (separate ? verification.verifiedTasks(row, work, false) : valid),
                canClose && verification.canExplainClose(row, work),
                summary,
                checked,
                delivery,
                !separate && scope != null && verification.canSubmitKnowledge(row, task, actor),
                canReplay,
                canReplay
                        ? queries.latestFeedbackReplay(row.requestId(), verification.replayAfter(task, result))
                        : null,
                task != null
                        && SOURCE_DATA.equals(task.direction())
                        && canWork(row, task, actor)
                        && task.reviewed()
                        && permissions.has(PROCESS));
    }

    /**
     * 展示单个任务的责任、当前结果及协调操作，正文仍受原问证据授权约束。
     */
    private FeedbackTaskVO taskDetail(Issue row, Task task, Progress progress, long actor, AccessScopeVO scope) {
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
                progress == null
                        ? null
                        : new FeedbackTaskVO.Progress(
                                visibleText(scope, progress.workDone()), visibleText(scope, progress.nextStep())),
                resolution(row, task, actor, scope, List.of()));
    }

    /**
     * 对筛选关键字保留字面匹配，空白输入交由 Mapper 省略条件。
     */
    private String keywordPattern(String keyword) {
        return keyword == null || keyword.isBlank() ? null : TextSearch.pattern(keyword);
    }
}
