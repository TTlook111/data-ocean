package com.dataocean.module.query.service;

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
import com.dataocean.module.knowledge.service.impl.KnowledgeVersionUse;
import com.dataocean.module.query.mapper.QueryConversationMapper;
import com.dataocean.module.query.mapper.QueryRequestMapper;
import com.dataocean.module.query.mapper.QueryRequestMapper.Input;
import com.dataocean.module.query.mapper.QueryRequestMapper.Permit;
import com.dataocean.module.query.mapper.QueryRequestMapper.Request;
import com.dataocean.module.query.service.QueryProtocol.Accepted;
import com.dataocean.module.query.service.QueryProtocol.AnswerRef;
import com.dataocean.module.query.service.QueryProtocol.Ask;
import com.dataocean.module.query.service.QueryProtocol.Conversation;
import com.dataocean.module.query.service.QueryProtocol.Data;
import com.dataocean.module.query.service.QueryProtocol.Evidence;
import com.dataocean.module.query.service.QueryProtocol.FeedbackQuestion;
import com.dataocean.module.query.service.QueryProtocol.RatingAnswer;
import com.dataocean.module.query.service.QueryProtocol.Reasoning;
import com.dataocean.module.query.service.QueryProtocol.RunInput;
import com.dataocean.module.query.service.QueryProtocol.Snapshot;
import com.dataocean.module.query.service.QueryProtocol.SourceOption;
import com.dataocean.module.query.service.QueryProtocol.Turn;

import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 单源问数的命令、实际运行与当前权限下的结果回读。
 */
@Service
@Slf4j
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
    /** 组装和复核已确认的同源多轮上下文。 */
    private final QueryContext context;
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
    private final KnowledgeVersionUse versions;
    /** 正式检索和确认依据。 */
    private final QueryCorpus corpus;
    /** 已准入模型与实际调用。 */
    private final QueryModels models;
    /** 受控 JDBC 执行和取消。 */
    private final QueryJdbc jdbc;
    /** 平台事务与业务源读分开。 */
    private final TransactionTemplate tx;
    /** 读写限量结果和执行依据。 */
    private final JsonCodec json;
    /** 指纹可用于核对，且不会保存绑定参数原文。 */
    private final Fingerprints fingerprints;
    /** 实际投影确定性规则和持久规则摘要。 */
    private final QueryOutput output = new QueryOutput();
    /** 运行中周期复核，跨重启仍从 MySQL 权威状态恢复。 */
    private final ConcurrentHashMap<String, Running> running = new ConcurrentHashMap<>();
    /** 本进程持有的可恢复代次，由独立心跳维护。 */
    private final ConcurrentHashMap<String, Integer> leases = new ConcurrentHashMap<>();
    /** 已终止的有限状态。 */
    private static final Set<String> TERMINAL = Set.of(
            "RESULT_DELIVERED",
            "PERMISSION_DENIED",
            "OUT_OF_SCOPE",
            "KNOWLEDGE_INSUFFICIENT",
            "RESOURCE_EXCEEDED",
            "TECHNICAL_FAILURE",
            "USER_CANCELLED",
            "WAIT_EXPIRED");

    /** 只列当前权威可用来源，禁用来源不再允许选择。 */
    public List<SourceOption> sources() {
        permissions.require("query.view");
        long user = UserContext.getUser();
        return access.current(user).stream()
                .map(AccessScopeVO::getSource)
                .filter(row -> "ENABLED"
                        .equals(catalog.source(Ids.parse(row.getId()), false).lifecycle()))
                .map(row -> new SourceOption(row.getId(), row.getName()))
                .toList();
    }

    /** 历史仅返回当前用户的实际同源问题摘要。 */
    public List<Turn> history(long source) {
        permissions.require("query.view");
        long user = UserContext.getUser();
        access.requireSource(user, source);
        return requests.history(user, source).stream()
                .map(row -> new Turn(row.id(), row.question(), row.state(), instant(row.createdAt())))
                .toList();
    }

    /** 同源工作台导航保持真实会话编号和最新轮次。 */
    public List<Conversation> conversations(long source) {
        permissions.require("query.view");
        long user = UserContext.getUser();
        access.requireSource(user, source);
        return conversations.history(user, source).stream()
                .map(row -> new Conversation(row.id(), row.title(), row.latestRequestId(), instant(row.updatedAt())))
                .toList();
    }

    /** 跨用户会话查询和撤权后的回读都拒绝。 */
    public List<Turn> turns(String id) {
        permissions.require("query.view");
        long user = UserContext.getUser();
        var conversation = conversations.get(uuid(id));
        if (conversation == null || conversation.userId() != user) {
            throw BizIllegalException.missing();
        }
        access.requireSource(user, conversation.sourceId());
        return conversations.turns(conversation.id()).stream()
                .map(row -> new Turn(row.id(), row.question(), row.state(), instant(row.createdAt())))
                .toList();
    }

    /** 接收原始问题和命令，网络重放不再生成新的轮次。 */
    public Accepted ask(Ask input, String header) {
        String command = uuid(header);
        long source = Ids.parse(input.sourceId());
        String question = text(input.question(), 600);
        String requestedParent = input.previousId() == null ? null : uuid(input.previousId());
        String requestedConversation = input.conversationId() == null ? null : uuid(input.conversationId());
        boolean inherit = !Boolean.FALSE.equals(input.inheritContext());
        var fingerprint = fingerprints.command(
                "query.submit",
                Ids.text(source),
                new Ask(Ids.text(source), question, requestedParent, requestedConversation, inherit));
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
                        : replay.sourceId() != source
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
                return new Accepted(replay.id());
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
            if (id.equals(conversationId)) {
                conversations.insert(id, actor.userId(), source, question, id);
            } else {
                var conversation = conversations.lock(conversationId);
                if (conversation == null
                        || conversation.userId() != actor.userId()
                        || conversation.sourceId() != source) {
                    throw BizIllegalException.missing();
                }
                parentId = parent == null ? conversation.latestRequestId() : parent.id();
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
                    fingerprint));
            return new Accepted(id);
        });
    }

    /** 补充在原轮次内仅接受一次，重放返回同一回执。 */
    public Accepted answer(String id, String header, String answer) {
        String command = uuid(header);
        String value = text(answer, 600);
        return tx.execute(status -> {
            guard.shared();
            var row = ownedRow(requests.lock(uuid(id)), UserContext.getUser());
            actorScope(row);
            if (command.equals(row.clarificationCommand())) {
                if (!value.equals(row.supplement())) {
                    throw BizIllegalException.conflict();
                }
                return new Accepted(row.id());
            }
            if (requests.answer(row.id(), command, value) == 1) {
                return new Accepted(row.id());
            }
            throw BizIllegalException.state("当前轮次已结束等待，请重新提问");
        });
    }

    /** 取消动作以相同轮次为防重范围，停止在途调用仍要核实实际结果。 */
    public Accepted cancel(String id) {
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
            return new Accepted(id);
        });
    }

    /** 回读与流式订阅共用同一归属和实时鉴权。 */
    public Snapshot read(String id) {
        return tx.execute(status -> {
            guard.shared();
            var row = owned(id, UserContext.getUser());
            permissions.require("query.view");
            var scope = access.requireSource(row.userId(), row.sourceId());
            Data data = null;
            Reasoning reasoning = null;
            String state = row.state();
            if (hasLiveResult(row)) {
                // 读结果和依据时使用实际当前规则及固定正式版本。
                requireReadableResult(row, scope);
                data = json.read(row.result(), Data.class);
                reasoning = json.read(row.reasoning(), Reasoning.class);
            }
            boolean sql =
                    reasoning != null && permissions.effective(row.userId()).contains("query.sql");
            return new Snapshot(
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
                    data == null ? null : new AnswerRef(row.id(), 1));
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
                new AnswerRef(row.id(), revision),
                row.userId(),
                row.sourceId(),
                row.executionId(),
                row.bindingId(),
                reasoning == null ? null : reasoning.evidence(),
                row.usageRefs());
    }

    /** 反馈工作区使用已核验的当前源范围回查原问，授权差异时不给出历史正文。 */
    public FeedbackQuestion feedbackQuestion(String id, AccessScopeVO scope) {
        var row = requests.get(uuid(id));
        if (row == null
                || !Ids.text(row.sourceId()).equals(scope.getSource().getId())
                || !output.signature(scope.getRules()).equals(evidenceRuleSignature(row))) {
            // 以原执行授权为可回看证据的上限，无法比较时保留事项基础信息。
            return null;
        }
        var reasoning = row.reasoning() == null ? null : json.read(row.reasoning(), Reasoning.class);
        Evidence evidence = reasoning == null ? null : reasoning.evidence();
        if (evidence != null && feedbackText(scope, evidence.title()) == null) {
            // 知识文档名称也可能含有当前受限的业务含义。
            evidence = null;
        }
        String dataScope = null;
        String sql = null;
        if (hasLiveResult(row)) {
            try {
                // 查询口径与 SQL 模板沿用结果留存和当前发布资格，不返回原值及参数。
                requireReadableResult(row, scope);
                dataScope =
                        feedbackText(scope, json.read(row.result(), Data.class).scope());
                if (reasoning != null && permissions.has("query.sql")) {
                    sql = feedbackText(scope, reasoning.sql());
                }
            } catch (BizIllegalException unavailable) {
                // 知识停用时仍保留可核对的原问和证据身份。
            }
        }
        String question = feedbackText(scope, row.question());
        return new FeedbackQuestion(
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
            if (explanation != null && "COMPLETED".equals(explanation.status())) {
                // 只发送已核过事实、未过期且当前绑定和输出规则仍允许的正文。
                requireReadableResult(row, scope);
                text = explanation.text();
            }
            return new Progress(
                    row.id(),
                    row.state(),
                    row.executionState(),
                    row.cancelRequested(),
                    instant(row.updatedAt()),
                    row.runVersion(),
                    explanation == null ? null : explanation.revision(),
                    explanation == null ? null : explanation.status(),
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

    /** 领取一个队列轮次，真实查询不阻塞其他模块的后台调度。 */
    @Scheduled(fixedDelay = 600, scheduler = "queryScheduler")
    void work() {
        var row = tx.execute(status -> {
            var next = requests.next();
            return next == null || requests.claim(next.id(), next.runVersion()) != 1 ? null : requests.get(next.id());
        });
        if (row == null) {
            return;
        }
        leases.put(row.id(), row.runVersion());
        String execution = row.executionState();
        String evidenceSignature = null;
        try {
            if ("FINALIZING".equals(row.state())) {
                // 业务 JDBC 已返回，进程恢复只做授权交付。
                finish(row);
                return;
            }
            // 外发前先锁定原用户、当前源与当前规则。
            var rules = checked(row).getRules();
            String signature = output.signature(rules);
            String question = row.supplement() == null ? row.question() : row.question() + "\n补充：" + row.supplement();
            output.checkDirectRequest(rules, question);
            evidenceSignature = signature;
            models.requireScope(row.sourceId());
            var frozen = prepare(row, signature, rules.isEmpty());
            var recent = frozen.memory().recent();
            var previous =
                    recent.isEmpty() ? null : recent.get(recent.size() - 1).plan();
            if (row.supplement() == null && previous == null && QueryDates.needsMonth(question)) {
                // 用户只给了日号，月份无法从当前对话唯一确定。
                updateStage(row, "GENERATING");
                waitForAnswer(row, "你说的是哪个月的这一天？");
                return;
            }
            var relativeDay = QueryDates.singleRelativeDay(question, businessDay(frozen.memory()));
            Integer adjacentDay = row.supplement() == null ? context.adjacentDay(row.question()) : null;
            if (adjacentDay != null && previous == null) {
                // 纯追问没有上文时先向本人求证，不从别的对话或文档示例猜测。
                updateStage(row, "GENERATING");
                waitForAnswer(row, "你想查什么数据？请补充商品或范围，以及要查询的日期。");
                return;
            }
            Long outputRevision = frozen.outputRevision();
            var run = new QueryModels.Run(row.id(), row.runVersion(), row.clarificationCommand() != null);
            var indexes = asActor(row, () -> {
                requireRules(row, signature);
                context.verify(row, signature, frozen.memory());
                return corpus.indexes(row.sourceId(), frozen.bindings(), remaining(row));
            });
            if (indexes.isEmpty()) {
                throw QueryFailure.knowledge();
            }
            List<QueryProtocol.Hit> hits;
            try {
                hits = models.retrieve(run, retrievalText(question, frozen.memory()), indexes, remaining(row));
            } finally {
                // 即使上游返回失败，也先落实在等待期间发生的取消与撤权。
                requireRules(row, signature);
            }
            if (hits.isEmpty()) {
                throw QueryFailure.knowledge();
            }
            var bases = asActor(row, () -> {
                requireRules(row, signature);
                return hits.stream()
                        .map(hit -> corpus.basis(row.sourceId(), hit))
                        .toList();
            });
            var evidence = bases.stream().map(QueryCorpus.Basis::snippet).toList();
            updateStage(row, "GENERATING");
            var named = QueryProductNames.resolve(bases, question);
            if (named != null && QueryProductNames.asksStock(question) && !named.confirmed()) {
                if (named.alternative() != null && !named.supplied() && row.supplement() == null) {
                    // 只问仍有歧义的规格，保留用户已经说出的日期。
                    waitForAnswer(
                            row, "你想查 " + named.size() + " 还是 " + named.alternative() + " 的" + named.name() + "？");
                    return;
                }
                if (named.simpleStock() && named.supplied()) {
                    // 发布依据仅确认了另一款规格，拒绝把它当成本题商品。
                    throw QueryFailure.knowledge();
                }
            }
            QueryProtocol.Plan namedPlan = null;
            if (named != null
                    && named.confirmed()
                    && named.simpleStock()
                    && (relativeDay != null || !QueryDates.mentionsSpecificDate(question))) {
                // 该问法只有已核验的商品、规格及单日范围，不让示例中的其他条件进入计划。
                namedPlan = new QuerySql().namedStock(named, relativeDay);
            }
            long generation = frozen.generationRevision();
            String planningQuestion =
                    relativeDay == null ? question : withBusinessDate(question, relativeDay.toString());
            QueryProtocol.Plan proposed;
            boolean generated = adjacentDay == null && namedPlan == null;
            if (namedPlan != null) {
                proposed = namedPlan;
            } else if (generated) {
                // 当前问题仍由正常规划节点识别意图和业务参数。
                proposed = QuerySql.normalize(models.plan(
                        run, generation, remaining(row), planningQuestion, evidence, rules, frozen.memory()));
            } else {
                // 只移动上一轮受核验的业务日，商品、口径和其他条件原样保留。
                var basis = selectBasis(bases, previous);
                proposed = new QuerySql().followBusinessDay(basis, previous, previous, adjacentDay);
                if (proposed == null) {
                    // 上轮若是时间范围或没有业务日期，就不能确定“前一天”。
                    waitForAnswer(row, "上一轮没有可作为基准的单个业务日期。请告诉我要查询的日期。");
                    return;
                }
                question = withBusinessDate(question, proposed.time().start());
            }
            switch (proposed.decision()) {
                case "READY" -> {
                    // 后续独立语义与输出核对使用已固定的提议。
                }
                case "CLARIFY" -> {
                    // 缺少改变业务结果的条件时，暂停在原轮次。
                    waitForAnswer(row, proposed.clarification());
                    return;
                }
                case "DENIED" -> throw QueryFailure.denied();
                case "NO_KNOWLEDGE" -> throw QueryFailure.knowledge();
                case "OUT_OF_SCOPE" -> throw new QueryFailure("OUT_OF_SCOPE");
                default -> throw QueryFailure.technical();
            }
            requireRules(row, signature);
            CheckedPlan checked;
            try {
                checked = compile(bases, proposed, previous, frozen.memory(), question);
            } catch (QueryFailure invalid) {
                if (!generated || !invalid.parameterMismatch()) {
                    // 已验证历史上的日期追问无需再次生成业务条件。
                    throw invalid;
                }
                // 首次草案从未获得执行许可。重规划仍使用原模型、权限、来源和剩余预算。
                requireRules(row, signature);
                var revised = models.repairParameter(
                        run, generation, remaining(row), planningQuestion, evidence, rules, proposed, frozen.memory());
                requireRules(row, signature);
                checked = compile(bases, QuerySql.normalize(revised), previous, frozen.memory(), question);
            }
            var plan = checked.plan();
            var basis = checked.basis();
            var compiled = checked.compiled();
            if (checked.inheritedDay()) {
                // 让独立审核看到本题已确定沿用的日期和用户修改的原话。
                question = withBusinessDate(question, plan.time().start());
            } else if (relativeDay != null
                    && plan.time() != null
                    && "DAY".equals(plan.time().mode())) {
                // 同一请求的规划、复核和实际查询使用同一个日历基准。
                question = withBusinessDate(question, plan.time().start());
            }
            if (namedPlan != null) {
                // 用户原话和发布依据的商品映射一起提交独立语义与输出核对。
                String confirmed = question + "\n发布依据已确认商品为 " + named.size() + " " + named.name() + "，商品编码 "
                        + named.code() + "。本题查询该规格的可售库存数量。";
                if (confirmed.length() <= 1210) {
                    question = confirmed;
                }
            }
            output.checkProjection(rules, basis, compiled.projection());
            output.checkDisclosure(rules, basis.binding().title(), compiled.scope());
            updateStage(row, "CHECKING");
            long semantic = frozen.semanticRevision();
            var selected = List.of(basis.snippet());
            if (!"ALLOW"
                    .equals(models.check(
                            run,
                            "semantic",
                            semantic,
                            remaining(row),
                            question,
                            selected,
                            rules,
                            plan,
                            frozen.memory()))) {
                throw QueryFailure.knowledge();
            }
            if (outputRevision != null) {
                requireRules(row, signature);
                if (!"ALLOW"
                        .equals(models.check(
                                run,
                                "output",
                                outputRevision,
                                remaining(row),
                                question,
                                selected,
                                rules,
                                plan,
                                frozen.memory()))) {
                    throw QueryFailure.denied();
                }
            }

            long binding = basis.binding().id();
            var revisions = new ArrayList<String>();
            if (generated) {
                // 这轮确实调用了规划节点，审计记录应包含实际模型版本。
                revisions.add(Ids.text(generation));
            }
            revisions.add(Ids.text(semantic));
            if (outputRevision != null) {
                // 只有需要且外发了独立输出核对时才登记。
                revisions.add(Ids.text(outputRevision));
            }
            var reasoning = new Reasoning(
                    basis.evidence(),
                    compiled.sql(),
                    compiled.projection(),
                    revisions,
                    plan,
                    basis.source().configurationId());
            var contextIds = new ArrayList<>(checked.parameterUse().contextRequestIds());
            if (adjacentDay != null || checked.inheritedDay()) {
                // 仅在实际沿用上轮日期时登记其确切轮次。
                contextIds.add(recent.get(0).requestId());
            }
            var parameterBases = new ArrayList<QueryCorpus.Basis>();
            if (namedPlan != null) {
                // 商品名称规格映射可能来自另一片实际命中。
                parameterBases.add(named.basis());
            }
            if (checked.parameterUse().published()) {
                // 编译时以正式片段核验了实际使用的参数。
                parameterBases.add(basis);
            }
            var usage = compiled.usage().withBasis(basis, parameterBases, contextIds);
            var executionFingerprint = fingerprints.command(
                    "query.execute", Ids.text(row.sourceId()), List.of(compiled.sql(), compiled.parameters()));
            running.put(row.id(), new Running(row, binding, signature));
            var data = jdbc.execute(
                    basis.source(),
                    row.id(),
                    compiled,
                    () -> remaining(row),
                    () -> tx.executeWithoutResult(status -> {
                        guard.shared();
                        users.lock(row.userId());
                        verifyBinding(row, binding, signature, basis.source().configurationId());
                        remaining(row);
                        if (requests.hasUnresolvedExecution(
                                row.userId(), row.sourceId(), executionFingerprint, row.id())) {
                            // 不为另一条原文不同、实际执行相同的轮次派发 SQL。
                            throw QueryFailure.technical();
                        }
                        if (requests.permit(new Permit(
                                        row.id(),
                                        row.runVersion(),
                                        UUID.randomUUID().toString(),
                                        binding,
                                        signature,
                                        json.write(reasoning),
                                        executionFingerprint,
                                        json.write(usage)))
                                != 1) {
                            throw new QueryFailure("USER_CANCELLED");
                        }
                    }));
            execution = "SUCCEEDED";
            if (requests.capture(row.id(), row.runVersion(), json.write(data)) != 1) {
                throw QueryFailure.technical();
            }
            finish(row);
        } catch (QueryFailure failure) {
            // HTTP 进程中断后放弃本机租约，新代次只核对已登记节点。
            if (!"RECOVERY_PENDING".equals(failure.state())) {
                stop(row, failure.state(), execution, failure.diagnostic(), evidenceSignature);
            }
        } catch (BizIllegalException denied) {
            stop(
                    row,
                    denied.getStatus() == 403 || denied.getStatus() == 401
                            ? "PERMISSION_DENIED"
                            : denied.getStatus() >= 500 ? "TECHNICAL_FAILURE" : "KNOWLEDGE_INSUFFICIENT",
                    execution,
                    null,
                    evidenceSignature);
        } catch (SQLException unavailable) {
            // 源端响应不明保留原执行号，禁止自动重发。
            stop(row, "TECHNICAL_FAILURE", "UNKNOWN", "SOURCE_EXECUTION_UNCERTAIN", evidenceSignature);
        } catch (RuntimeException failure) {
            // 操作原话、SQL 参数、凭据及提供方响应都不进入日志。
            log.warn("问数轮次中止，requestId={}，异常类={}", row.id(), failure.getClass().getSimpleName());
            stop(row, "TECHNICAL_FAILURE", execution, null, evidenceSignature);
        } finally {
            leases.remove(row.id(), row.runVersion());
            running.remove(row.id());
        }
    }

    /**
     * 基于原轮次的澄清使用同一等待、取消检查和预算边界。
     */
    private void waitForAnswer(Request row, String text) {
        if (text == null || text.isBlank() || requests.waitForAnswer(row.id(), row.runVersion(), text.strip()) != 1) {
            // 只接受当前代次且仍处于可澄清状态的请求。
            throw QueryFailure.knowledge();
        }
    }

    /**
     * 将服务端由本人上文解出的日期送审，实际问题显示仍用用户原话。
     */
    private String withBusinessDate(String question, String day) {
        String clarified = question + "\n系统确认的查询业务日期：" + day + "。";
        return clarified.length() <= 1210 ? clarified : question;
    }

    /**
     * 计划必须落在实际召回的同版知识与可用字段内。
     */
    private QueryCorpus.Basis selectBasis(List<QueryCorpus.Basis> bases, QueryProtocol.Plan plan) {
        if (plan == null || !"READY".equals(plan.decision()) || plan.fields() == null) {
            // 缺失候选不能携带到业务数据库。
            throw QueryFailure.knowledge();
        }
        return bases.stream()
                .filter(value -> Ids.text(value.binding().id()).equals(plan.binding()))
                .filter(value -> plan.fields().stream().allMatch(value.labels()::containsKey))
                .findFirst()
                .orElseThrow(QueryFailure::knowledge);
    }

    /**
     * 优先绑定同一主题明确沿用的日期，再用正常业务默认和受控编译验证。
     */
    private CheckedPlan compile(
            List<QueryCorpus.Basis> bases,
            QueryProtocol.Plan plan,
            QueryProtocol.Plan previous,
            QueryProtocol.Memory memory,
            String question) {
        var basis = selectBasis(bases, plan);
        var compiler = new QuerySql();
        var inherited =
                compiler.keepsBusinessDay(question) ? compiler.followBusinessDay(basis, plan, previous, 0) : null;
        boolean hasHistory = !memory.recent().isEmpty() || !memory.earlier().isEmpty();
        var resolved = inherited != null
                ? inherited
                : compiler.resolveTime(basis, plan, businessDay(memory), question, hasHistory);
        var compiled = jdbc.compile(basis, resolved);
        var parameterUse = compiler.checkProductCodes(basis, resolved, question, memory);
        return new CheckedPlan(resolved, basis, compiled, inherited != null, parameterUse);
    }

    /** 澄清和任务恢复都沿用请求接收时的北京时间日期。 */
    private LocalDate businessDay(QueryProtocol.Memory memory) {
        return Instant.parse(memory.timeAnchor())
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toLocalDate();
    }

    /** 已编译的实际候选进入独立的语义、输出和时序核验。 */
    private record CheckedPlan(
            QueryProtocol.Plan plan,
            QueryCorpus.Basis basis,
            QuerySql.Compiled compiled,
            boolean inheritedDay,
            QueryUsage.ParameterUse parameterUse) {}

    /** 定期检查实际在途执行的当前资格并尝试取消，独立于查询工作线程。 */
    @Scheduled(fixedDelay = 1000)
    void observe() {
        leases.forEach((id, run) -> requests.renew(id, run));
        running.forEach((id, run) -> {
            try {
                remaining(run.row());
                verifyBinding(run.row(), run.binding(), run.signature(), null);
            } catch (RuntimeException cannotContinue) {
                jdbc.cancel(id);
            }
        });
    }

    /** 修复遗留待核实状态与过期结果，不自动重发已派发的 SQL。 */
    @Scheduled(fixedDelay = 15000)
    void expire() {
        requests.expire();
        requests.expireResults();
        explanations.expire();
    }

    /** 首次解析固定模型修订与对话证据，随后只复用该快照。 */
    private RunInput prepare(Request row, String signature, boolean noRules) {
        if (row.runInput() != null) {
            var saved = json.read(row.runInput(), RunInput.class);
            if (!signature.equals(saved.signature())) {
                throw QueryFailure.denied();
            }
            return saved;
        }
        return asActor(row, () -> {
            requireRules(row, signature);
            var bindings = corpus.bindings(row.sourceId());
            if (bindings.isEmpty()) {
                // 没有生效知识时确立来源级缺口，不调用模型猜测原因。
                throw QueryFailure.noPublishedKnowledge();
            }
            var frozen = new RunInput(
                    signature,
                    context.capture(row, signature),
                    bindings,
                    models.revision("sql-generate"),
                    models.revision("sql-semantic-check"),
                    noRules ? null : models.revision("output-meaning"));
            if (requests.freeze(row.id(), row.runVersion(), json.write(frozen)) != 1) {
                throw QueryFailure.technical();
            }
            return frozen;
        });
    }

    /** 检索使用有界同源上下文，步骤回执指纹同时绑定原提问和已确认条件。 */
    private String retrievalText(String question, QueryProtocol.Memory memory) {
        if (memory.recent().isEmpty()) {
            return question;
        }
        var prior = memory.recent().get(memory.recent().size() - 1);
        var metric = prior.plan() == null ? "" : Objects.toString(prior.plan().metric(), "");
        return question + "\n同源上轮：" + prior.question() + "\n已确认指标：" + metric;
    }

    /** 已捕获结果可被新调度进程交付，只重新核对权限和固定版本。 */
    private void finish(Request row) {
        tx.executeWithoutResult(status -> {
            guard.shared();
            var captured = requests.get(row.id());
            if (captured.runVersion() != row.runVersion() || !"FINALIZING".equals(captured.state())) {
                throw QueryFailure.technical();
            }
            var settings = json.read(captured.reasoning(), Reasoning.class);
            verifyBinding(row, captured.bindingId(), captured.ruleSignature(), settings.configuration());
            if (requests.deliver(row.id(), row.runVersion()) != 1) {
                // 原状态仍由台账决定，取消意图和租约竞争不会在这里冒充取消成功。
                throw QueryFailure.technical();
            }
            explanations.initial(row.id());
        });
    }

    /** 当前请求取消、过期以及权限变化都先于下一外发节点。 */
    private AccessScopeVO checked(Request row) {
        var latest = requests.get(row.id());
        if (latest.runVersion() != row.runVersion()
                || latest.leaseUntil() == null
                || !latest.leaseUntil().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            throw QueryFailure.technical();
        }
        if (latest.cancelRequested() || TERMINAL.contains(latest.state())) {
            throw new QueryFailure("USER_CANCELLED");
        }
        return actorScope(row);
    }

    /** 实时核对发起人有效权限和实际数据源授权。 */
    private AccessScopeVO actorScope(Request row) {
        actors.require(row.userId(), row.authenticationVersion(), "query.view", "query.ask");
        return access.requireSource(row.userId(), row.sourceId());
    }

    /** 当前规则的变更必须重新请求执行，本轮旧模型检查不复用。 */
    private void requireRules(Request row, String signature) {
        if (!signature.equals(output.signature(checked(row).getRules()))) {
            throw QueryFailure.denied();
        }
    }

    /** 冻结的绑定、权限和连接配置在执行及交付前原子复核。 */
    private void verifyBinding(Request row, long binding, String signature, Long configuration) {
        requireRules(row, signature);
        asActor(row, () -> {
            if (configuration != null
                    && !configuration.equals(
                            catalog.source(row.sourceId(), true).configurationId())) {
                throw QueryFailure.technical();
            }
            return versions.requireForQuery(row.sourceId(), binding);
        });
    }

    /** 将可信发起者临时安装给必须复用会话检查的下游。 */
    private <T> T asActor(Request row, Supplier<T> read) {
        return actors.asActor(row.userId(), row.authenticationVersion(), read, "query.view", "query.ask");
    }

    /** 更新可订阅进度，已经取消的轮次无后续生成。 */
    private void updateStage(Request row, String stage) {
        if (requests.stage(row.id(), row.runVersion(), stage) != 1) {
            throw new QueryFailure("USER_CANCELLED");
        }
    }

    /** 活动预算至少剩下一秒才能进入下一步。 */
    private int remaining(Request row) {
        long spent = Duration.between(instant(row.processingStartedAt()), Instant.now())
                .toMillis();
        long seconds = (row.remainingMillis() - spent) / 1000;
        if (seconds < 1) {
            throw QueryFailure.resource();
        }
        return (int) Math.min(120, seconds);
    }

    /** 只在确实已登记执行时保留运行、实际成功或未知记录。 */
    private void stop(Request row, String state, String execution, String reason, String signature) {
        var latest = requests.get(row.id());
        String actual =
                latest.executionId() == null ? "NOT_SUBMITTED" : "SUCCEEDED".equals(execution) ? execution : "UNKNOWN";
        String diagnostic =
                switch (state) {
                    case "KNOWLEDGE_INSUFFICIENT" -> reason == null ? "INSUFFICIENT_KNOWLEDGE" : reason;
                    case "TECHNICAL_FAILURE" -> reason == null ? "QUERY_RUNTIME_FAILURE" : reason;
                    default -> null;
                };
        requests.stop(row.id(), row.runVersion(), state, actual, diagnostic, signature);
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

    /** 平台 DATETIME 均按 UTC 解释。 */
    private Instant instant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    /** 在途观察只保留核对所需的权威值。 */
    private record Running(Request row, long binding, String signature) {}
}
