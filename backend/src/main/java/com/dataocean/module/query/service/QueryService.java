package com.dataocean.module.query.service;

import static com.dataocean.common.Times.instant;
import static com.dataocean.module.query.service.QueryProtocol.TERMINAL;

import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.Fingerprints;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.access.entity.vo.AccessScopeVO;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ServiceActorPermission;
import com.dataocean.module.knowledge.service.KnowledgeVersionService;
import com.dataocean.module.query.entity.dto.AskQueryDTO;
import com.dataocean.module.query.entity.vo.FeedbackQuestionVO;
import com.dataocean.module.query.entity.vo.QueryAcceptedVO;
import com.dataocean.module.query.entity.vo.QueryAnswerRefVO;
import com.dataocean.module.query.entity.vo.QueryConversationVO;
import com.dataocean.module.query.entity.vo.QueryDataVO;
import com.dataocean.module.query.entity.vo.QueryEvidenceVO;
import com.dataocean.module.query.entity.vo.QuerySnapshotVO;
import com.dataocean.module.query.entity.vo.QuerySourceOptionVO;
import com.dataocean.module.query.entity.vo.QueryTurnVO;
import com.dataocean.module.query.mapper.QueryConversationMapper;
import com.dataocean.module.query.mapper.QueryRequestMapper;
import com.dataocean.module.query.mapper.QueryRequestMapper.Input;
import com.dataocean.module.query.mapper.QueryRequestMapper.Request;
import com.dataocean.module.query.service.QueryProtocol.RatingAnswer;
import com.dataocean.module.query.service.QueryProtocol.Reasoning;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

/**
 * 单源问数的命令、实际运行与当前权限下的结果回读。
 */
@Service
@RequiredArgsConstructor
public class QueryService {

    /** 与授权修改共享放行锁。 */
    private final GuardMapper guard;
    /** 轮次的确切本人与固化状态。 */
    private final QueryRequestMapper requests;
    /** 会话与本人同源轮次的真实归属。 */
    private final QueryConversationMapper conversations;
    /** 串行同一用户的防重命令，避免并行新建孤立会话。 */
    private final UserMapper users;
    /**
     * 在同一可信身份下核对澄清时的当前来源权限。
     */
    private final QueryRunAccess runAccess;
    /**
     * 新问数接收前核对问题理解节点的可用配置。
     */
    private final QueryModels models;
    /** 交付后独立跟踪解释修订。 */
    private final QueryExplanations explanations;
    /** 当前数据源访问与输出限制。 */
    private final AccessScopeService access;
    /** 重新核对后台任务发起人的当前资格。 */
    private final ServiceActorPermission actors;
    /** 当前分开的 SQL 回看权限。 */
    private final PermissionService permissions;
    /** 当前目录与可用连接。 */
    private final GovernanceCatalog catalog;
    /** 固定版本当前是否可用。 */
    private final KnowledgeVersionService versions;
    /** 平台事务与业务源读分开。 */
    private final TransactionTemplate tx;
    /** 读写限量结果和执行依据。 */
    private final JsonCodec json;
    /** 指纹可用于核对，且不会保存绑定参数原文。 */
    private final Fingerprints fingerprints;

    /**
     * 回看和反馈证据采用统一输出规则。
     */
    private final QueryOutput output = new QueryOutput();
    /** 只列当前权威可用来源，禁用来源不再允许选择。 */
    public List<QuerySourceOptionVO> sources() {
        permissions.require("query.view");
        long user = UserContext.getUser();
        return access.current(user).stream()
                .map(AccessScopeVO::getSource)
                .filter(row -> "ENABLED"
                        .equals(catalog.source(Ids.parse(row.getId()), false).lifecycle()))
                .map(row -> new QuerySourceOptionVO(row.getId(), row.getName()))
                .toList();
    }

    /** 历史仅返回当前用户的实际同源问题摘要。 */
    public List<QueryTurnVO> history(long source) {
        permissions.require("query.view");
        long user = UserContext.getUser();
        access.requireSource(user, source);
        return requests.history(user, source).stream()
                .map(row -> new QueryTurnVO(row.id(), row.question(), row.state(), instant(row.createdAt())))
                .toList();
    }

    /** 同源工作台导航保持真实会话编号和最新轮次。 */
    public List<QueryConversationVO> conversations(long source) {
        permissions.require("query.view");
        long user = UserContext.getUser();
        access.requireSource(user, source);
        return conversations.history(user, source).stream()
                .map(row ->
                        new QueryConversationVO(row.id(), row.title(), row.latestRequestId(), instant(row.updatedAt())))
                .toList();
    }

    /** 跨用户会话查询和撤权后的回读都拒绝。 */
    public List<QueryTurnVO> turns(String id) {
        permissions.require("query.view");
        long user = UserContext.getUser();
        var conversation = conversations.get(uuid(id));
        if (conversation == null || conversation.userId() != user) {
            throw BizIllegalException.missing();
        }
        access.requireSource(user, conversation.sourceId());
        return conversations.turns(conversation.id()).stream()
                .map(row -> new QueryTurnVO(row.id(), row.question(), row.state(), instant(row.createdAt())))
                .toList();
    }

    /** 接收原始问题和命令，网络重放不再生成新的轮次。 */
    public QueryAcceptedVO ask(AskQueryDTO input, String header) {
        return submit(input, header, null);
    }

    /** 在办理人的当前权限下重跑原问，保留原补充和原业务日锚点。 */
    public QueryAcceptedVO replayFeedback(String id, AccessScopeVO scope, String command) {
        var original = requests.get(uuid(id));
        if (original == null || !canReplayFeedback(id, scope)) {
            throw BizIllegalException.state("原问题的条件无法完整、安全地用于复验");
        }
        return submit(
                new AskQueryDTO(Ids.text(original.sourceId()), original.question(), null, null, false),
                command,
                original);
    }

    /** 只复用当前可核对的原问与补充，不把其他人的对话历史传给模型。 */
    public boolean canReplayFeedback(String id, AccessScopeVO scope) {
        var row = requests.get(uuid(id));
        return row != null
                && Ids.text(row.sourceId()).equals(scope.getSource().getId())
                && Objects.equals(evidenceRuleSignature(row), feedbackRuleSignature(scope))
                && feedbackText(scope, row.question()) != null
                && (row.supplement() == null || feedbackText(scope, row.supplement()) != null)
                && (row.previousId() == null || !row.inheritContext());
    }

    /** 办理详情只恢复本人的原问题复验轮次。 */
    public String latestFeedbackReplay(String origin, LocalDateTime submittedAt) {
        return requests.latestFeedbackReplay(uuid(origin), UserContext.getUser(), submittedAt);
    }

    /** 核对复验真实轮次、执行身份、现行输出规则和命中的正式绑定。 */
    public boolean feedbackReplayMatches(
            String id,
            String origin,
            long actor,
            long source,
            long binding,
            LocalDateTime publishedAt,
            String ruleSignature,
            boolean passed) {
        var row = requests.get(uuid(id));
        if (!matchesFeedbackReplay(row, origin, actor, source, publishedAt, ruleSignature)) {
            return false;
        }
        var original = requests.get(origin);
        return passed
                ? "RESULT_DELIVERED".equals(row.state())
                        && "SUCCEEDED".equals(row.executionState())
                        && row.executedAt() != null
                        && Objects.equals(row.bindingId(), binding)
                        && !Objects.equals(original.bindingId(), row.bindingId())
                        && Objects.equals(row.supplement(), original.supplement())
                : Set.of("RESULT_DELIVERED", "KNOWLEDGE_INSUFFICIENT", "TECHNICAL_FAILURE")
                        .contains(row.state());
    }

    /**
     * 源数据复验允许沿用当前可用的原知识版本，成功须有实际只读交付结果。
     */
    public boolean sourceFeedbackReplayMatches(
            String id,
            String origin,
            long actor,
            long source,
            LocalDateTime effectiveAfter,
            String ruleSignature,
            boolean passed) {
        var row = requests.get(uuid(id));
        if (!matchesFeedbackReplay(row, origin, actor, source, effectiveAfter, ruleSignature)) {
            return false;
        }
        // 问数未交付可登记未通过，但不能作为修正已经生效的依据。
        if (!passed) {
            return Set.of("RESULT_DELIVERED", "KNOWLEDGE_INSUFFICIENT", "TECHNICAL_FAILURE")
                    .contains(row.state());
        }
        var original = requests.get(origin);
        if (original == null
                || !"RESULT_DELIVERED".equals(row.state())
                || !"SUCCEEDED".equals(row.executionState())
                || row.executedAt() == null
                || row.bindingId() == null
                || !Objects.equals(original.supplement(), row.supplement())) {
            return false;
        }
        versions.requireForFeedbackReplay(actor, source, row.bindingId());
        return true;
    }

    /**
     * 核对原问复验的归属、来源、起点时间和当前输出规则。
     */
    private boolean matchesFeedbackReplay(
            Request row, String origin, long actor, long source, LocalDateTime after, String ruleSignature) {
        return row != null
                && row.userId() == actor
                && row.sourceId() == source
                && origin.equals(row.recheckOriginId())
                && !row.createdAt().isBefore(after)
                && Objects.equals(ruleSignature, evidenceRuleSignature(row));
    }

    /** 普通问数和治理复验共用命令防重、认证、排队及真实执行路径。 */
    private QueryAcceptedVO submit(AskQueryDTO input, String header, Request original) {
        String command = uuid(header);
        long source = Ids.parse(input.getSourceId());
        String question = text(input.getQuestion(), 600);
        String requestedParent = input.getPreviousId() == null ? null : uuid(input.getPreviousId());
        String requestedConversation = input.getConversationId() == null ? null : uuid(input.getConversationId());
        boolean inherit = !Boolean.FALSE.equals(input.getInheritContext());
        var normalized = new AskQueryDTO(Ids.text(source), question, requestedParent, requestedConversation, inherit);
        var fingerprint = original == null
                ? fingerprints.command("query.submit", Ids.text(source), normalized)
                : fingerprints.command("query.feedback.replay", original.id(), normalized);
        var actor = UserContext.current();
        return tx.execute(status -> {
            guard.shared();
            users.lock(actor.userId());
            actors.require(actor.userId(), actor.authenticationVersion(), "query.view", "query.ask");
            access.requireSource(actor.userId(), source);
            var replay = requests.byCommand(actor.userId(), command);
            if (replay != null) {
                // 重放只返回原收据，不把最新会话指针作为隐式新参数。
                boolean mismatched = replay.commandFingerprint() != null
                        ? !MessageDigest.isEqual(replay.commandFingerprint(), fingerprint)
                        : original != null
                                || replay.sourceId() != source
                                || !replay.question().equals(question)
                                || replay.inheritContext() != inherit
                                || (requestedParent != null && !requestedParent.equals(replay.previousId()))
                                || (requestedConversation != null
                                        && !requestedConversation.equals(replay.conversationId()))
                                || (requestedParent == null
                                        && requestedConversation == null
                                        && replay.previousId() != null);
                if (mismatched) {
                    throw BizIllegalException.conflict();
                }
                return new QueryAcceptedVO(replay.id());
            }
            String id = UUID.randomUUID().toString();
            var parent = requestedParent == null ? null : owned(requestedParent, actor.userId());
            if (requests.hasUnresolved(actor.userId(), source, question)
                    || parent != null && "UNKNOWN".equals(parent.executionState())) {
                throw BizIllegalException.state("原查询执行状态尚未核实");
            }
            var conversationId = requestedConversation != null
                    ? requestedConversation
                    : parent != null ? parent.conversationId() : id;
            String parentId = requestedParent;
            if (parent != null && (parent.sourceId() != source || !conversationId.equals(parent.conversationId()))) {
                throw BizIllegalException.missing();
            }
            if (!id.equals(conversationId)) {
                // 先完成会话归属核验，再向合法请求反馈配置状态。
                var conversation = conversations.lock(conversationId);
                if (conversation == null
                        || conversation.userId() != actor.userId()
                        || conversation.sourceId() != source) {
                    throw BizIllegalException.missing();
                }
                parentId = parent == null ? conversation.latestRequestId() : parent.id();
            }
            // 在新轮次写入任何状态前确认配置，重放已有命令仍取原收据。
            models.requireIntentReady(source);
            if (id.equals(conversationId)) {
                conversations.insert(id, actor.userId(), source, question, id);
            } else {
                conversations.use(conversationId, id);
            }
            requests.insert(new Input(
                    id,
                    actor.userId(),
                    actor.authenticationVersion(),
                    source,
                    command,
                    parentId,
                    question,
                    conversationId,
                    inherit,
                    fingerprint,
                    original == null ? null : original.id(),
                    original == null ? null : original.supplement()));
            return new QueryAcceptedVO(id);
        });
    }

    /** 补充在原轮次内仅接受一次，重放返回同一回执。 */
    public QueryAcceptedVO answer(String id, String header, String answer) {
        String command = uuid(header);
        String value = text(answer, 600);
        return tx.execute(status -> {
            guard.shared();
            var row = ownedRow(requests.lock(uuid(id)), UserContext.getUser());
            runAccess.actorScope(row);
            if (command.equals(row.clarificationCommand())) {
                if (!value.equals(row.supplement())) {
                    throw BizIllegalException.conflict();
                }
                return new QueryAcceptedVO(row.id());
            }
            if (requests.answer(row.id(), command, value) == 1) {
                return new QueryAcceptedVO(row.id());
            }
            throw BizIllegalException.state("当前轮次已结束等待，请重新提问");
        });
    }

    /** 取消动作以相同轮次为防重范围，停止在途调用仍要核实实际结果。 */
    public QueryAcceptedVO cancel(String id) {
        var actor = UserContext.current();
        return tx.execute(status -> {
            guard.shared();
            var row = ownedRow(requests.lock(uuid(id)), actor.userId());
            access.requireSource(actor.userId(), row.sourceId());
            if (!TERMINAL.contains(row.state())) {
                requests.cancel(id);
            } else if ("RESULT_DELIVERED".equals(row.state())) {
                explanations.stop(id);
            }
            return new QueryAcceptedVO(id);
        });
    }

    /** 回读与流式订阅共用同一归属和实时鉴权。 */
    public QuerySnapshotVO read(String id) {
        return tx.execute(status -> {
            guard.shared();
            var row = owned(id, UserContext.getUser());
            permissions.require("query.view");
            var scope = access.requireSource(row.userId(), row.sourceId());
            QueryDataVO data = null;
            Reasoning reasoning = null;
            String state = row.state();
            if (hasLiveResult(row)) {
                // 读结果和依据时使用实际当前规则及固定正式版本。
                requireReadableResult(row, scope);
                data = json.read(row.result(), QueryDataVO.class);
                reasoning = json.read(row.reasoning(), Reasoning.class);
            }
            boolean sql =
                    reasoning != null && permissions.effective(row.userId()).contains("query.sql");
            return new QuerySnapshotVO(
                    row.id(),
                    Ids.text(row.sourceId()),
                    row.question(),
                    row.supplement(),
                    state,
                    row.executionState(),
                    row.cancelRequested(),
                    row.clarification(),
                    data,
                    reasoning == null ? null : reasoning.evidence(),
                    sql ? reasoning.sql() : null,
                    instant(row.createdAt()),
                    instant(row.executedAt()),
                    instant(row.updatedAt()),
                    row.conversationId(),
                    row.runVersion(),
                    data == null ? null : explanations.view(row.id()),
                    data == null ? null : new QueryAnswerRefVO(row.id(), 1));
        });
    }

    /** 评价及重放使用原提问人、当前资格和确切的已交付回答。 */
    public RatingAnswer ratingAnswer(String id, int revision, boolean lock) {
        if (revision != 1) {
            // 当前查询只交付一个基础回答修订。
            throw BizIllegalException.missing();
        }
        guard.shared();
        var principal = UserContext.current();
        var answer = uuid(id);
        if (lock) {
            // 与现有问数写入沿用相同的安全锁、人员锁和请求锁顺序。
            users.lock(principal.userId());
        }
        var row = lock
                ? ownedRow(requests.lockOwned(answer, principal.userId()), principal.userId())
                : owned(answer, principal.userId());
        actors.require(principal.userId(), principal.authenticationVersion(), "query.view", "query.ask");
        var scope = access.requireSource(row.userId(), row.sourceId());
        if (!hasLiveResult(row)) {
            // 只对仍存在于当前交付窗口的结果接受评价。
            throw BizIllegalException.state("本次回答尚未交付或结果已超过保留期");
        }
        requireReadableResult(row, scope);
        var reasoning = row.reasoning() == null ? null : json.read(row.reasoning(), Reasoning.class);
        return new RatingAnswer(
                new QueryAnswerRefVO(row.id(), revision),
                row.userId(),
                row.sourceId(),
                row.executionId(),
                row.bindingId(),
                reasoning == null ? null : reasoning.evidence(),
                row.usageRefs());
    }

    /** 反馈工作区使用已核验的当前源范围回查原问，授权差异时不给出历史正文。 */
    public FeedbackQuestionVO feedbackQuestion(String id, AccessScopeVO scope) {
        var row = requests.get(uuid(id));
        if (row == null
                || !Ids.text(row.sourceId()).equals(scope.getSource().getId())
                || !output.signature(scope.getRules()).equals(evidenceRuleSignature(row))) {
            // 以原执行授权为可回看证据的上限，无法比较时保留事项基础信息。
            return null;
        }
        var reasoning = row.reasoning() == null ? null : json.read(row.reasoning(), Reasoning.class);
        QueryEvidenceVO evidence = reasoning == null ? null : reasoning.evidence();
        if (evidence != null && feedbackText(scope, evidence.getTitle()) == null) {
            // 知识文档名称也可能含有当前受限的业务含义。
            evidence = null;
        }
        String dataScope = null;
        String sql = null;
        if (hasLiveResult(row)) {
            try {
                // 查询口径与 SQL 模板沿用结果留存和当前发布资格，不返回原值及参数。
                requireReadableResult(row, scope);
                dataScope = feedbackText(
                        scope, json.read(row.result(), QueryDataVO.class).getScope());
                if (reasoning != null && permissions.has("query.sql")) {
                    sql = feedbackText(scope, reasoning.sql());
                }
            } catch (BizIllegalException unavailable) {
                // 知识停用时仍保留可核对的原问和证据身份。
            }
        }
        String question = feedbackText(scope, row.question());
        return new FeedbackQuestionVO(
                row.id(),
                question,
                feedbackText(scope, row.supplement()),
                instant(row.executedAt()),
                evidence,
                dataScope,
                sql,
                question == null || evidence == null || row.usageRefs() == null || dataScope == null);
    }

    /** 原问、反馈原因及后台自由文本沿用相同的输出限制。 */
    public String feedbackText(AccessScopeVO scope, String text) {
        if (text == null) {
            // 到期的内容保持缺失，不以当前事实或占位文字冒充原文。
            return null;
        }
        try {
            output.checkSupported(scope.getRules());
            output.checkDisclosure(scope.getRules(), text);
            return text;
        } catch (QueryFailure restricted) {
            // 单项受限仅隐藏该字段，基础办理记录继续可用。
            return null;
        }
    }

    /** 回告按原提问人当前授权核对，返回定位资格但不读取结果正文。 */
    public boolean canOpenFeedbackAnswer(String id, long recipient, AccessScopeVO scope) {
        var row = requests.get(uuid(id));
        return row != null
                && row.userId() == recipient
                && Ids.text(row.sourceId()).equals(scope.getSource().getId())
                && Objects.equals(evidenceRuleSignature(row), feedbackRuleSignature(scope))
                && feedbackText(scope, row.question()) != null;
    }

    /** 人工复验签名和回告权限沿用问数真实输出规则的定义。 */
    public String feedbackRuleSignature(AccessScopeVO scope) {
        return output.signature(scope.getRules());
    }

    /** 成功执行用许可摘要，未拿到执行许可的失败使用生成前已核验的范围。 */
    private String evidenceRuleSignature(Request row) {
        return row.ruleSignature() != null ? row.ruleSignature() : row.failureRuleSignature();
    }

    /** 与结果回看共用交付状态和到期边界。 */
    private boolean hasLiveResult(Request row) {
        return "RESULT_DELIVERED".equals(row.state())
                && row.result() != null
                && row.executedAt() != null
                && row.executedAt().isAfter(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
    }

    /** 使用当前授权和原绑定，历史记录不能绕过当前规则。 */
    private void requireReadableResult(Request row, AccessScopeVO scope) {
        if (!output.signature(scope.getRules()).equals(row.ruleSignature())) {
            // 旧输出签名无法代表当前限制。
            throw BizIllegalException.denied();
        }
        versions.requireForQuery(row.sourceId(), row.bindingId());
    }

    /** 订阅沿用当前源权限，完整答复通过结果回看门禁后才随状态交付。 */
    public Progress progress(String id, long user) {
        return tx.execute(status -> {
            guard.shared();
            var row = owned(id, user);
            var scope = access.requireSource(user, row.sourceId());
            var explanation = hasLiveResult(row) ? explanations.view(row.id()) : null;
            String text = null;
            if (explanation != null && "COMPLETED".equals(explanation.getStatus())) {
                // 只发送已核过事实、未过期且当前绑定和输出规则仍允许的正文。
                requireReadableResult(row, scope);
                text = explanation.getText();
            }
            return new Progress(
                    row.id(),
                    row.state(),
                    row.executionState(),
                    row.cancelRequested(),
                    instant(row.updatedAt()),
                    row.runVersion(),
                    explanation == null ? null : explanation.getRevision(),
                    explanation == null ? null : explanation.getStatus(),
                    text);
        });
    }

    /**
     * 同一请求的状态和可交付答复使用同一个 SSE 游标。
     *
     * @param explanationText 已通过结果回看及文本校验的完整答复
     */
    public record Progress(
            String id,
            String state,
            String executionState,
            boolean cancelRequested,
            Instant updatedAt,
            int runVersion,
            Integer explanationRevision,
            String explanationStatus,
            String explanationText) {
        /** 查询和独立解释都结束后关闭订阅。 */
        public boolean terminal() {
            return TERMINAL.contains(state)
                    && !"QUEUED".equals(explanationStatus)
                    && !"GENERATING".equals(explanationStatus);
        }
    }

    /** 原问题及历史答案只向本人定位。 */
    private Request owned(String id, long user) {
        return ownedRow(requests.get(uuid(id)), user);
    }

    /** 已锁与普通读取都以可信本人核对同一轮次。 */
    private Request ownedRow(Request row, long user) {
        if (row == null || row.userId() != user) {
            throw BizIllegalException.missing();
        }
        return row;
    }

    /** 普通命令和请求路径采用稳定随机 UUID。 */
    private String uuid(String value) {
        try {
            if (value != null && value.length() == 36) {
                return UUID.fromString(value).toString();
            }
        } catch (IllegalArgumentException invalid) {
            // 不回显外部传入内容。
        }
        throw BizIllegalException.invalid("请求编号格式不正确");
    }

    /** 合理限制用户输入，输入错误不启动任务。 */
    private String text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw BizIllegalException.invalid("请输入有效的问数内容");
        }
        return value.strip();
    }
}
