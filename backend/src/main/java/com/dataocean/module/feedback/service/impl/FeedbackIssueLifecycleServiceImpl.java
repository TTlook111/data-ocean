package com.dataocean.module.feedback.service.impl;

import static com.dataocean.common.Times.instant;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.ASSIGN;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.CLOSED_OTHER;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.IN_PROGRESS;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.MERGED;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.PROCESS;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.RESOLVED;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.TO_VERIFY;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.WAITING;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.assignable;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.commandId;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.displayStatus;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.optionalText;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.requireIssue;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.requiredText;

import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.access.entity.vo.AccessScopeVO;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.feedback.entity.dto.DisposeFeedbackSupplementDTO;
import com.dataocean.module.feedback.entity.dto.FeedbackIssueReasonDTO;
import com.dataocean.module.feedback.entity.dto.MergeFeedbackIssueDTO;
import com.dataocean.module.feedback.entity.dto.RequestFeedbackSupplementDTO;
import com.dataocean.module.feedback.entity.dto.SubmitFeedbackSupplementDTO;
import com.dataocean.module.feedback.entity.vo.FeedbackLifecycleVO;
import com.dataocean.module.feedback.entity.vo.FeedbackSupplementVO;
import com.dataocean.module.feedback.mapper.FeedbackLifecycleMapper;
import com.dataocean.module.feedback.mapper.FeedbackLifecycleMapper.Origin;
import com.dataocean.module.feedback.mapper.FeedbackLifecycleMapper.Supplement;
import com.dataocean.module.feedback.mapper.FeedbackMapper;
import com.dataocean.module.feedback.mapper.FeedbackMapper.Issue;
import com.dataocean.module.feedback.mapper.FeedbackTaskMapper;
import com.dataocean.module.feedback.mapper.FeedbackTaskMapper.Task;
import com.dataocean.module.feedback.service.FeedbackIssueLifecycleService;
import com.dataocean.module.feedback.service.FeedbackNoticeService;
import com.dataocean.module.feedback.service.FeedbackQueryIssueIntake;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.query.service.QueryService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 在原始事项上办理补充、主从关系及新完成轮次。
 */
@Service
@RequiredArgsConstructor
public class FeedbackIssueLifecycleServiceImpl implements FeedbackIssueLifecycleService {

    /**
     * 事项队列与操作台账。
     */
    private final FeedbackMapper feedback;

    /**
     * 与权限、人员状态及授权变化协调。
     */
    private final GuardMapper guard;

    /**
     * 查询当前办理及证据权限。
     */
    private final PermissionService permissions;

    /**
     * 核对账号状态或串行同一账号的办理命令。
     */
    private final UserMapper users;

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
     * 原问补充、主从关系与完成轮次。
     */
    private final FeedbackLifecycleMapper lifecycle;

    /**
     * 在办理事务中统一锁定、去重和审计。
     */
    private final FeedbackIssueCommands mutations;

    /**
     * 原问题分别核权后才返回接收人或补充正文，成员编号保留独立进入入口。
     */
    FeedbackLifecycleVO lifecycleDetail(Issue row, long actor, AccessScopeVO scope, List<Task> work) {
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
                // 每条原问的规则签名分别核对。
                var question = queries.feedbackQuestion(origin.requestId(), scope);
                if (question != null) {
                    // 可读原问才可出现在目标和正文中。
                    readable.add(origin.requestId());
                    if (!pending.contains(origin.requestId())) {
                        // 已有待回复请求时保留历史，避免生成第二条等待。
                        targets.add(new FeedbackLifecycleVO.Recipient(
                                Ids.text(origin.issueId()),
                                origin.requestId(),
                                origin.recipientName(),
                                question.getQuestion()));
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
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO disposeSupplement(
            String id, String supplementId, String command, DisposeFeedbackSupplementDTO input) {
        long issue = Ids.parse(id);
        var request = lifecycle.supplement(commandId(supplementId));
        if (request == null) {
            // 只接受确切的补充请求编号。
            throw BizIllegalException.missing();
        }
        String disposition = input.getDisposition();
        if (!"WITHDRAWN".equals(disposition) && !"UNAVAILABLE".equals(disposition)) {
            // 明确区别主动撤回和实际未能取得信息。
            throw BizIllegalException.invalid("请选择有效的补充处置方式");
        }
        var normalized = new DisposeFeedbackSupplementDTO(
                input.getExpectedVersion(), disposition, requiredText(input.getReason(), "请填写处置原因"));
        Long peer = request.issueId() == issue ? null : request.issueId();
        return mutations.change(
                id,
                command,
                PROCESS,
                "feedback.supplement.dispose",
                input.getExpectedVersion(),
                List.of(request.id(), normalized),
                peer,
                (main, member) -> {
                    // 在主从事项锁内重新核对处置权和等待状态。
                    long actor = UserContext.getUser();
                    if (!canHandleSupplement(main, actor)) {
                        // 转派后只允许当前主负责人处置等待。
                        throw BizIllegalException.denied();
                    }
                    if (member != null && !Objects.equals(member.mergedInto(), main.id())) {
                        // 补充始终归原事项，纠正合并后的旧主事项不能代办。
                        throw BizIllegalException.missing();
                    }
                    if (lifecycle.dispose(request.id(), actor, disposition, normalized.getReason()) != 1) {
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
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO askSupplement(String id, String command, RequestFeedbackSupplementDTO input) {
        long issue = Ids.parse(id);
        var normalized = new RequestFeedbackSupplementDTO(
                input.getExpectedVersion(), input.getRequestId(), requiredText(input.getPrompt(), "请填写具体缺项"));
        var origin = findRelatedOrigin(issue, normalized.getRequestId()).orElse(null);
        if (origin == null) {
            // 先观察到关系解除，再回查事务回执，已完成的提交仍可重放。
            return mutations.requireReplay(id, command, PROCESS, normalized);
        }
        Long peer = origin.issueId() == issue ? null : origin.issueId();
        return mutations.change(
                id,
                command,
                PROCESS,
                "feedback.supplement.ask",
                input.getExpectedVersion(),
                normalized,
                peer,
                (row, member) -> {
                    // 在两端事项锁内核对当前负责人、原问和收件权限。
                    long actor = UserContext.getUser();
                    if (!canAskSupplement(row, actor)) {
                        // 已转派、结案或缺少当期核查时，结束操作。
                        throw BizIllegalException.denied();
                    }
                    var current =
                            findRelatedOrigin(row.id(), origin.requestId()).orElseThrow(BizIllegalException::missing);
                    if (current.issueId() != origin.issueId()
                            || (member != null && !Objects.equals(member.mergedInto(), row.id()))) {
                        // 锁前后关联须一致，等待期间发生纠正合并时要求重新核对。
                        throw BizIllegalException.conflict();
                    }
                    requireEvidence(row.sourceId(), current.requestId());
                    var recipientScope = ownQueryScope(current, row.sourceId());
                    if (visibleText(recipientScope, normalized.getPrompt()) == null) {
                        // 向原提问人发送的正文也遵守对方当前输出范围。
                        throw BizIllegalException.state("补充问题超出原提问人当前可见范围");
                    }
                    if (lifecycle.pending(current.requestId()) != null) {
                        // 每条原问同时只允许一条待回复请求。
                        throw BizIllegalException.state("该原问题已有待补充请求");
                    }
                    lifecycle.appendSupplement(
                            UUID.randomUUID().toString(),
                            current.issueId(),
                            current.requestId(),
                            normalized.getPrompt(),
                            null);
                    if (member != null) {
                        // 原事项和主事项分别记账，保持各自的历史入口和版本。
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
    @Override
    @Transactional
    public FeedbackSupplementVO ownSupplement(String requestId) {
        guard.shared();
        permissions.require("query.view");
        var origin = lifecycle.ownOrigin(commandId(requestId), UserContext.getUser());
        if (origin == null) {
            // 没有关联反馈时原问无需展示补充入口。
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
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO submitSupplement(String requestId, String command, SubmitFeedbackSupplementDTO input) {
        guard.shared();
        permissions.require("query.view");
        long actor = UserContext.getUser();
        var origin = lifecycle.ownOrigin(commandId(requestId), actor);
        if (origin == null) {
            // 本人原问未关联反馈时拒绝新增回复。
            throw BizIllegalException.missing();
        }
        var snapshot = requireIssue(feedback.issue(origin.issueId()));
        ownQueryScope(origin, snapshot.sourceId());
        var answer = requiredText(input.getAnswer(), "请填写业务补充说明");
        var normalized = new SubmitFeedbackSupplementDTO(
                input.getExpectedVersion(), optionalText(input.getSupplementId()), answer);
        return mutations.change(
                Ids.text(origin.issueId()),
                command,
                "query.view",
                "feedback.supplement.submit",
                input.getExpectedVersion(),
                List.of(origin.requestId(), normalized),
                snapshot.mergedInto(),
                (row, parent) -> {
                    // 提交期间主从关系变化后要以最新事实重新打开表单。
                    if (!Objects.equals(snapshot.mergedInto(), row.mergedInto())) {
                        // 当前锁定的主事项必须仍然包含该原事项。
                        throw BizIllegalException.conflict();
                    }
                    var main = parent == null ? row : parent;
                    ownQueryScope(origin, row.sourceId());
                    if (!assignable(main)) {
                        // 已经结案的主事项不再接收本轮补充。
                        throw BizIllegalException.state("当前反馈事项已结束");
                    }
                    var pending = lifecycle.pending(origin.requestId());
                    if (pending != null) {
                        // 旧草稿不能答复后来新建的请求。
                        if (!Objects.equals(pending.id(), normalized.getSupplementId())
                                || lifecycle.answer(pending.id(), origin.requestId(), answer) != 1) {
                            // 只允许更新这次表单指向且仍待回复的请求。
                            throw BizIllegalException.conflict();
                        }
                    } else {
                        // 开放主动补充的知识缺口可保存独立说明。
                        if (normalized.getSupplementId() != null
                                || !FeedbackQueryIssueIntake.KNOWLEDGE_GAP.equals(origin.origin())) {
                            // 拒绝过期请求的再次回复以及其他来源的主动补充。
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
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO merge(String id, String command, MergeFeedbackIssueDTO input) {
        long target = Ids.parse(input.getTargetId());
        if (Ids.parse(id) == target) {
            // 主从事项需要独立编号，禁止关联自身。
            throw BizIllegalException.invalid("主事项和重复事项须为不同记录");
        }
        var normalized = new MergeFeedbackIssueDTO(
                input.getExpectedVersion(),
                Ids.text(target),
                input.getTargetVersion(),
                requiredText(input.getReason(), "请填写相同根因和处理范围的依据"));
        return mutations.change(
                id,
                command,
                ASSIGN,
                "feedback.issue.merge",
                input.getExpectedVersion(),
                normalized,
                target,
                (row, main) -> {
                    // 两端分别核对调用者确认的版本及在办资格。
                    if (main.version() != input.getTargetVersion()) {
                        // 目标主事项也可能在确认后被改动。
                        throw BizIllegalException.conflict();
                    }
                    if (main.sourceId() != row.sourceId()
                            || !assignable(main)
                            || !canMerge(row, tasks.list(row.id(), true))) {
                        // 源和处理范围均合适才允许建立关系。
                        throw BizIllegalException.state("请选择同源的未结主事项及尚无独立处理结果的重复事项");
                    }
                    requireEvidence(row.sourceId(), row.requestId());
                    requireEvidence(main.sourceId(), main.requestId());
                    if (lifecycle.merge(row.id(), row.version(), main.id()) != 1) {
                        // 条件写入结果须与受锁的副本一致。
                        throw BizIllegalException.conflict();
                    }
                    advanceEvidence(main, true);
                    long actor = UserContext.getUser();
                    lifecycle.activity(row.id(), row.version() + 1, actor, "MERGED", normalized.getReason(), main.id());
                    lifecycle.activity(
                            main.id(), main.version() + 1, actor, "MEMBER_ADDED", normalized.getReason(), row.id());
                    return List.of(new ChangeVO("mainIssueId", "主事项编号", null, Ids.text(main.id())));
                });
    }

    /**
     * 解除错误合并，原事项的补充、证据和关系历史均继续归原事项。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO unmerge(String id, String command, FeedbackIssueReasonDTO input) {
        var snapshot = requireIssue(feedback.issue(Ids.parse(id)));
        var normalized =
                new FeedbackIssueReasonDTO(input.getExpectedVersion(), requiredText(input.getReason(), "请填写纠正合并的依据"));
        return mutations.change(
                id,
                command,
                ASSIGN,
                "feedback.issue.unmerge",
                input.getExpectedVersion(),
                normalized,
                snapshot.mergedInto(),
                (row, main) -> {
                    // 只解除当前仍存在且指向已核对主事项的关系。
                    if (main == null || !Objects.equals(row.mergedInto(), main.id()) || !MERGED.equals(row.status())) {
                        // 避免纠正已有其他主事项或已经独立办理的记录。
                        throw BizIllegalException.state("当前事项没有可纠正的合并关系");
                    }
                    if (lifecycle.unmerge(row.id(), row.version(), main.id()) != 1) {
                        // 保留其他并发动作更新过的关系。
                        throw BizIllegalException.conflict();
                    }
                    advanceEvidence(main, assignable(main));
                    long actor = UserContext.getUser();
                    lifecycle.activity(
                            row.id(), row.version() + 1, actor, "UNMERGED", normalized.getReason(), main.id());
                    lifecycle.activity(
                            main.id(), main.version() + 1, actor, "MEMBER_REMOVED", normalized.getReason(), row.id());
                    return List.of(new ChangeVO("mainIssueId", "主事项编号", Ids.text(main.id()), null));
                });
    }

    /**
     * 根据新证据开启新完成轮次，复验、任务结果及旧回告继续保留在历史中。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO reopen(String id, String command, FeedbackIssueReasonDTO input) {
        var normalized = new FeedbackIssueReasonDTO(
                input.getExpectedVersion(), requiredText(input.getReason(), "请填写新证据或同范围复发依据"));
        return mutations.change(
                id, command, PROCESS, "feedback.issue.reopen", input.getExpectedVersion(), normalized, row -> {
                    // 重开先核对角色和当前已结案事实，再撤回当期引用。
                    long actor = UserContext.getUser();
                    if (!canReopen(row, actor)) {
                        // 仅保留负责人或协调者的重开入口。
                        throw BizIllegalException.denied();
                    }
                    if (lifecycle.reopen(row.id(), row.version()) != 1) {
                        // 每个已核对版本只能开启一次新轮次。
                        throw BizIllegalException.conflict();
                    }
                    tasks.reopen(row.id());
                    lifecycle.activity(row.id(), row.version() + 1, actor, "REOPENED", normalized.getReason(), null);
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
            // 任一版本冲突都回滚主从两边的写入。
            throw BizIllegalException.conflict();
        }
        if (invalidate) {
            // 仅在复验依据发生变动时撤销任务复验指针。
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
    private Optional<Origin> findRelatedOrigin(long issue, String request) {
        return lifecycle.origins(issue).stream()
                .filter(origin -> origin.requestId().equals(request))
                .findFirst();
    }

    /**
     * 证据读取能力和原问题的当前业务范围缺一不可。
     */
    private void requireEvidence(long source, String request) {
        permissions.require("feedback.evidence");
        permissions.require("query.view");
        var scope = access.findSource(UserContext.getUser(), source).orElse(null);
        if (scope == null || queries.feedbackQuestion(request, scope) == null) {
            // 实际原问依照当前授权和输出规则可见才可继续办理。
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
            // 本人身份、来源授权和原问输出条件必须均有效。
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
                instant(item.answeredAt()),
                item.disposition(),
                staff ? visibleText(scope, item.dispositionReason()) : null,
                instant(item.disposedAt()));
    }

    /**
     * 正文随对应原问证据可见性裁剪。
     */
    private String visibleText(AccessScopeVO scope, String text) {
        return scope == null ? null : queries.feedbackText(scope, text);
    }
}
