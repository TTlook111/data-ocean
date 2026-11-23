package com.dataocean.module.feedback.service.impl;

import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.CLOSED_OTHER;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.IN_PROGRESS;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.PROCESS;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.RESOLVED;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.SOURCE_DATA;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.SUBMITTED;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.TO_VERIFY;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.VERIFY;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.VIEW;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.active;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.canWork;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.owner;

import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.Fingerprints;
import com.dataocean.module.access.entity.vo.AccessOriginVO;
import com.dataocean.module.access.entity.vo.AccessScopeVO;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.feedback.mapper.FeedbackLifecycleMapper;
import com.dataocean.module.feedback.mapper.FeedbackMapper;
import com.dataocean.module.feedback.mapper.FeedbackMapper.Issue;
import com.dataocean.module.feedback.mapper.FeedbackResolutionMapper;
import com.dataocean.module.feedback.mapper.FeedbackResolutionMapper.ResultRecord;
import com.dataocean.module.feedback.mapper.FeedbackResolutionMapper.Verification;
import com.dataocean.module.feedback.mapper.FeedbackTaskMapper;
import com.dataocean.module.feedback.mapper.FeedbackTaskMapper.Task;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper.Binding;
import com.dataocean.module.knowledge.service.KnowledgeVersionService;
import com.dataocean.module.query.service.QueryService;

import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 列表、详情和写入共用的当前复验资格与结案事实。
 */
@Component
@RequiredArgsConstructor
class FeedbackVerificationPolicy {

    /**
     * 说明结案支持的事实类型，与受控回告模板一致。
     */
    private static final Set<String> OTHER_REASONS =
            Set.of("NOT_REPRODUCED", "DEFINITION_CONFIRMED", "CANCELLED", "OTHER");

    /**
     * 事项队列与操作台账。
     */
    private final FeedbackMapper feedback;

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
     * 生成请求或授权上下文的受控指纹。
     */
    private final Fingerprints fingerprints;

    /**
     * 读取和持久化当前结果及复验。
     */
    private final FeedbackResolutionMapper resolutions;

    /**
     * 核对正式发布的当前使用资格。
     */
    private final KnowledgeVersionService versions;

    /**
     * 核对复验所用数据源及其依赖。
     */
    private final GovernanceCatalog catalog;

    /**
     * 维护事项下独立任务及当前结果引用。
     */
    private final FeedbackTaskMapper tasks;

    /**
     * 原问补充、主从关系与完成轮次。
     */
    private final FeedbackLifecycleMapper lifecycle;

    /**
     * 已通过当前复验的结果退出复验待办，资格或依据变化后可以重新登记。
     */
    boolean canVerifyResult(Issue row, Task task, ResultRecord result, boolean valid, long actor) {
        return !row.pendingSupplement()
                && TO_VERIFY.equals(row.status())
                && result != null
                && (task == null || SUBMITTED.equals(task.status()))
                && !valid
                && permissions.has(VERIFY)
                && eligibleHandler(row, task)
                && verificationContext(actor, row.sourceId()) != null;
    }

    /**
     * 治理负责人须具备已发布版本和原问的核查资格。
     */
    boolean canSubmitKnowledge(Issue row, Task task, long actor) {
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

    /**
     * 重跑原问与读取其依据分别要求当前范围和对应操作权限。
     */
    AccessScopeVO replayScope(long actor, long source) {
        var codes = permissions.effective(actor);
        return codes.containsAll(Set.of("feedback.evidence", "query.view", "query.ask"))
                ? access.findSource(actor, source).orElse(null)
                : null;
    }

    /**
     * 发布、暂停及撤下以当前锁内的权威版本为准。
     */
    Binding publishedBinding(long source, long id, boolean lock) {
        var origin = catalog.source(source, lock);
        var binding = versions.current(source, id);
        if (versions.isPaused(binding.scopeId())) {
            // 暂停期间该发布无法作为当前修复依据。
            throw BizIllegalException.state("关联的知识版本已暂停使用");
        }
        versions.requireBasis(origin, binding);
        return binding;
    }

    /**
     * 治理复验的实际问数须晚于处理范围变动，并采用对应的现行绑定。
     */
    boolean matchesRecheck(
            Issue row, Task task, ResultRecord result, long verifier, String request, boolean passed, boolean lock) {
        var scope = replayScope(verifier, row.sourceId());
        if (request == null || scope == null || !queries.canReplayFeedback(row.requestId(), scope)) {
            // 原问及实际复验必须受同一当前范围约束。
            return false;
        }
        try {
            // 源数据修正可沿用原发布，按当前可用发布和实际只读查询结果核对。
            if (SOURCE_DATA.equals(result.impact())) {
                // 源系统修复可沿用当前仍可用的原发布。
                if (lock) {
                    // 结案写事务同步锁定来源，避免调查范围同时变更。
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
                // 旧证据所用发布无法证明已经完成治理修正。
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
            // 发布或源授权变更使这次复验依据失效，保留历史。
            return false;
        }
    }

    /**
     * 知识和源数据任务可使用原问题只读复验。
     */
    boolean supportsReplay(ResultRecord result) {
        return result.knowledgeBindingId() != null || SOURCE_DATA.equals(result.impact());
    }

    /**
     * 任务范围变化后，知识复验必须引用新范围确定后实际启动的轮次。
     */
    LocalDateTime replayAfter(Task task, ResultRecord result) {
        return task != null && task.recheckAfter().isAfter(result.submittedAt())
                ? task.recheckAfter()
                : result.submittedAt();
    }

    /**
     * 读取事项或任务当前引用的结果版本。
     */
    ResultRecord currentResult(Issue row, Task task) {
        Integer version = task == null ? row.resultVersion() : task.resultVersion();
        return version == null ? null : resolutions.result(row.id(), version);
    }

    /**
     * 结案时在来源锁内重新核对当期处理结果、发布绑定及复验人的授权。
     */
    boolean validVerification(Issue row, Task task, Verification record, boolean lock) {
        boolean accepted = record != null
                && "PASSED".equals(record.decision())
                && Objects.equals(task == null ? row.resultVersion() : task.resultVersion(), record.resultVersion())
                && eligibleHandler(row, task)
                && record.contextSignature().equals(verificationContext(record.verifierId(), row.sourceId()));
        if (!accepted) {
            // 先核对版本、负责人和权限签名，失配则无需读取实际问数。
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
    boolean verifiedTasks(Issue row, List<Task> work, boolean lock) {
        var active = work.stream().filter(FeedbackIssueRules::active).toList();
        return !active.isEmpty()
                && eligibleHandler(row, null)
                && active.stream()
                        .allMatch(task -> SUBMITTED.equals(task.status())
                                && validVerification(row, task, currentVerification(row, task), lock));
    }

    /**
     * 每个人名下的来源和输出规则都要使用当前版本，比对时不展示凭据或业务数据。
     */
    String verificationContext(long tester, long source) {
        var user = users.selectById(tester);
        if (user == null || !"ENABLED".equals(user.getLifecycle()) || user.isMustChangePassword()) {
            // 停用或尚未完成初始化的账号无法继续提供复验依据。
            return null;
        }
        var codes = permissions.effective(tester);
        if (!codes.containsAll(Set.of(VIEW, VERIFY, "query.view"))) {
            // 功能权限失效时，旧签名也不再有效。
            return null;
        }
        var scope = access.findSource(tester, source).orElse(null);
        if (scope == null) {
            // 来源撤权后不继续为原数据范围签名。
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
    boolean eligibleHandler(Issue row, Task task) {
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
    void requireCandidate(long assignee) {
        if (!eligibleAssignee(assignee)) {
            // 列表开过后人员状态仍可能变更，写入时重新检查。
            throw BizIllegalException.state("所选人员当前不能办理反馈事项");
        }
    }

    /**
     * 按事项或任务引用的确切版本查人工复验。
     */
    Verification currentVerification(Issue row, Task task) {
        Integer version = task == null ? row.verificationVersion() : task.verificationVersion();
        return version == null ? null : resolutions.verification(row.id(), version);
    }

    /**
     * 没有必要任务仍在办且主负责人具备本期核查时，允许说明结案。
     */
    boolean canExplainClose(Issue row, List<Task> work) {
        return IN_PROGRESS.equals(row.status())
                && !row.pendingSupplement()
                && work.stream().noneMatch(FeedbackIssueRules::active)
                && row.assigneeId() != null
                && feedback.hasCurrentReview(row.id(), row.assigneeId());
    }

    /**
     * 已解决和说明结案各有事实条件，仅返回条件满足后的固定回告模板。
     */
    String closureTemplate(Issue row, String outcome, String reason, boolean lock) {
        requireCompleteEvidence(row);
        var work = tasks.list(row.id(), lock);
        if (RESOLVED.equals(outcome)) {
            // 按当前事项或所有独立任务核对已通过的复验。
            boolean verified = work.isEmpty()
                    ? validVerification(row, null, currentVerification(row, null), lock)
                    : verifiedTasks(row, work, lock);
            if (!TO_VERIFY.equals(row.status()) || !verified) {
                // 有效复验和事项阶段都必须支持已解决结案。
                throw BizIllegalException.state("当前处理结果尚未通过有效复验");
            }
            return RESOLVED;
        }
        if (CLOSED_OTHER.equals(outcome)
                && reason != null
                && OTHER_REASONS.contains(reason)
                && canExplainClose(row, work)) {
            // 使用已核对的具体原因匹配固定回告模板。
            return reason;
        }
        throw BizIllegalException.state("请核对当前核查记录和其他结案原因");
    }

    /**
     * 必要补充未齐时，保留已有任务结果，只阻止登记复验和结案。
     */
    void requireCompleteEvidence(Issue row) {
        if (row.pendingSupplement()) {
            // 仍有必要补充时不能作出复验或结案结论。
            throw BizIllegalException.state("请等待必要的用户补充后再复验或结案");
        }
    }
}
