package com.dataocean.module.query.service;

import static com.dataocean.common.Times.instant;

import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.infrastructure.Fingerprints;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.access.entity.vo.OutputRuleVO;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.UserMapper;
import com.dataocean.module.query.entity.vo.QueryDataVO;
import com.dataocean.module.query.mapper.QueryRequestMapper;
import com.dataocean.module.query.mapper.QueryRequestMapper.Permit;
import com.dataocean.module.query.mapper.QueryRequestMapper.Request;
import com.dataocean.module.query.service.QueryCorpus.Basis;
import com.dataocean.module.query.service.QueryProtocol.Condition;
import com.dataocean.module.query.service.QueryProtocol.Field;
import com.dataocean.module.query.service.QueryProtocol.Reasoning;
import com.dataocean.module.query.service.QueryProtocol.RerankOrder;
import com.dataocean.module.query.service.QueryProtocol.RunInput;
import com.dataocean.module.query.service.QueryProtocol.SummaryDecision;
import com.dataocean.module.query.service.QuerySql.ValueComparison;
import com.dataocean.module.query.service.QueryTableSelection.Scope;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 调度、核验、执行与交付已提交的问数轮次。
 */
@Slf4j
@Service
@RequiredArgsConstructor
class QueryWorker {

    /** 可选节点最多等待两次短请求，余量不足时给必要步骤保留时间。 */
    private static final int MIN_OPTIONAL_BUDGET_SECONDS = 35;

    /**
     * 只补充物理表时，恢复同一轮的日期计算。
     */
    private static final String TABLE_CLARIFICATION = "有多个符合条件的同名表。请用模式名.表名指定要查询的表。";

    /**
     * 相邻日需要确认原表上要移动的日期条件。
     */
    private static final String ADJACENT_DATE_CLARIFICATION = "上一轮无法确定要调整的日期条件。请说明要调整哪一列，如有新日期请一并给出。";

    /**
     * 识别已经进入补充状态的旧请求。
     */
    private static final String LEGACY_DATE_CLARIFICATION = "上一轮无法确定唯一的日期基准。请说明要调整哪个日期条件，或直接给出查询日期。";

    /**
     * 自然周需要确认原表上的逐日指标。
     */
    private static final String PREVIOUS_WEEK_CLARIFICATION = "请确认上周需要逐日查询的指标。";

    /**
     * 与权限及配置变更协调执行许可。
     */
    private final GuardMapper guard;

    /**
     * 登记租约、进度、执行事实及交付状态。
     */
    private final QueryRequestMapper requests;

    /**
     * 在执行许可落盘前锁定原发起者。
     */
    private final UserMapper users;

    /**
     * 核对与当前轮次绑定的会话和追问。
     */
    private final QueryContext context;

    /**
     * 交付后建立独立解读任务。
     */
    private final QueryExplanations explanations;

    /**
     * 复核原发起人、发布和输出许可。
     */
    private final QueryRunAccess runAccess;

    /**
     * 读取正式索引与可用证据。
     */
    private final QueryCorpus corpus;

    /**
     * 通过原轮次调用固定节点版本。
     */
    private final QueryModels models;

    /**
     * 执行与取消已核准的只读 SQL。
     */
    private final QueryJdbc jdbc;

    /**
     * 明确分隔登记执行与返回交付的事务。
     */
    private final TransactionTemplate tx;

    /**
     * 保存类型化运行上下文和执行事实。
     */
    private final JsonCodec json;

    /**
     * 为实际 SQL 和绑定参数生成防重指纹。
     */
    private final Fingerprints fingerprints;

    /**
     * 用同一标准核对执行计划的投影和披露范围。
     */
    private final QueryOutput output = new QueryOutput();

    /**
     * 处于实际执行期的本机轮次，用于独立复核和取消。
     */
    private final ConcurrentHashMap<String, Running> running = new ConcurrentHashMap<>();

    /**
     * 当前工作者领取的租约，由独立心跳续租。
     */
    private final ConcurrentHashMap<String, Integer> leases = new ConcurrentHashMap<>();

    /**
     * 领取一个队列轮次，真实查询不阻塞其他模块的后台调度。
     */
    @Scheduled(fixedDelay = 600, scheduler = "queryScheduler")
    void work() {
        var row = tx.execute(status -> {
            // 领取与读取权威代次在同一事务内完成。
            var next = requests.next();
            return next == null || requests.claim(next.id(), next.runVersion()) != 1 ? null : requests.get(next.id());
        });
        if (row == null) {
            // 空队列或领取竞争无需建立本机租约。
            return;
        }
        leases.put(row.id(), row.runVersion());
        String execution = row.executionState();
        String evidenceSignature = null;
        try {
            // 按台账恢复最后一阶段，否则从尚未执行的状态继续。
            if ("FINALIZING".equals(row.state())) {
                // 业务 JDBC 已返回，进程恢复只做授权交付。
                finish(row);
                return;
            }
            // 外发前先锁定原用户、当前源与当前规则。
            var rules = runAccess.checked(row).getRules();
            String signature = output.signature(rules);
            String question = row.supplement() == null ? row.question() : row.question() + "\n补充：" + row.supplement();
            output.checkDirectRequest(rules, question);
            evidenceSignature = signature;
            var prepared = preparePlan(row, signature, question, rules);
            if (prepared == null) {
                // 澄清已推入同一轮的等待状态，本次处理无需继续执行。
                return;
            }
            var data = execute(row, signature, prepared);
            execution = "SUCCEEDED";
            output.checkResult(rules, data, json);
            if (requests.capture(row.id(), row.runVersion(), json.write(data)) != 1) {
                // 捕获只允许写入当前代次的执行结果。
                throw QueryFailure.technical();
            }
            finish(row);
        } catch (QueryFailure failure) {
            // HTTP 进程中断后放弃本机租约，新代次只核对已登记节点。
            if (!"RECOVERY_PENDING".equals(failure.state())) {
                // 结果待核实时仅交还租约，明确结论才进入终态。
                stop(row, failure.state(), execution, failure.diagnostic(), evidenceSignature);
            }
        } catch (BizIllegalException denied) {
            // 保留原来的撤权、知识不足与技术失败区分。
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
     * 固定请求输入、验算检索与计划并核对投影。需要澄清时回到同一轮，交由提问人继续。
     */
    private PreparedPlan preparePlan(Request row, String signature, String question, List<OutputRuleVO> rules) {
        models.requireScope(row.sourceId());
        var frozen = prepare(row, signature, rules.isEmpty());
        var run = new QueryModels.Run(row.id(), row.runVersion(), row.clarificationCommand() != null);
        var history = history(row, signature, frozen, run);
        var memory = history.memory();
        var recent = memory.recent();
        var previous = recent.isEmpty() ? null : recent.get(recent.size() - 1).plan();
        if (row.supplement() == null && previous == null && QueryDates.needsMonth(question)) {
            // 用户只给了日号，月份无法从当前对话唯一确定。
            updateStage(row, "GENERATING");
            waitForAnswer(row, "你说的是哪个月的这一天？");
            return null;
        }
        var requestedDay = QueryDates.requestedDay(row.question(), row.supplement(), businessDay(memory));
        boolean keepsDateFollowup = row.supplement() == null || TABLE_CLARIFICATION.equals(row.clarification());
        boolean clarifiedAdjacentDate = row.supplement() != null
                && (ADJACENT_DATE_CLARIFICATION.equals(row.clarification())
                        || LEGACY_DATE_CLARIFICATION.equals(row.clarification()));
        boolean clarifiedHistoryDate = clarifiedAdjacentDate
                || row.supplement() != null && PREVIOUS_WEEK_CLARIFICATION.equals(row.clarification());
        Integer adjacentDay = keepsDateFollowup ? context.adjacentDay(row.question()) : null;
        boolean asksPreviousWeek = frozen.historySummaryRevision() != null
                && keepsDateFollowup
                && QueryDates.previousWeekFollowup(row.question());
        if ((adjacentDay != null || asksPreviousWeek) && previous == null) {
            // 纯追问没有上文时先向本人求证，不从别的对话或文档示例猜测。
            updateStage(row, "GENERATING");
            waitForAnswer(row, asksPreviousWeek ? "上周你想查什么指标？请补充商品或范围。"
                    : "你想查什么数据？请补充商品或范围，以及要查询的日期。");
            return null;
        }
        Long outputRevision = frozen.outputRevision();
        var retrieval = retrieveBasis(row, signature, question, frozen, run, memory);
        if (retrieval.intent() != null && Boolean.TRUE.equals(retrieval.intent().ambiguous())) {
            // 用户范围不完整时保留原轮次，由用户补充后再规划。
            updateStage(row, "GENERATING");
            waitForAnswer(row, "你想包含或排除哪些业务范围？");
            return null;
        }
        var terms = retrieval.intent() == null ? null : retrieval.intent().conditions();
        var scope = planningScope(row, signature, frozen, memory, retrieval.bases());
        var bases = scope.bases();
        if (bases.isEmpty()) {
            // 完成可用的定向核对之后，仍无正式依据才返回知识不足。
            throw QueryFailure.knowledge();
        }
        var evidence = bases.stream().map(QueryCorpus.Basis::snippet).toList();
        updateStage(row, "GENERATING");
        var named = QueryProductNames.resolve(bases, question);
        if (named != null && QueryProductNames.asksStock(question) && !named.confirmed()) {
            // 已识别商品但规格不唯一时按发布依据澄清或拒绝。
            if (named.alternative() != null && !named.supplied() && row.supplement() == null) {
                // 只问仍有歧义的规格，保留用户已经说出的日期。
                waitForAnswer(row, "你想查 " + named.size() + " 还是 " + named.alternative() + " 的" + named.name() + "？");
                return null;
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
                && (requestedDay != null || !QueryDates.mentionsSpecificDate(question))) {
            // 该问法只有已核验的商品、规格及单日范围，不让示例中的其他条件进入计划。
            namedPlan = new QuerySql().namedStock(named, requestedDay);
        }
        long generation = frozen.generationRevision();
        Long usedRepair = null;
        String planningQuestion = requestedDay == null ? question : withBusinessDate(question, requestedDay.toString());
        QueryProtocol.Plan proposed;
        boolean followsWeek = asksPreviousWeek;
        boolean generated = adjacentDay == null && !followsWeek && namedPlan == null;
        if (namedPlan != null) {
            // 采用正式片段确认的单日查询计划。
            proposed = namedPlan;
        } else if (generated) {
            // 当前问题仍由正常规划节点识别意图和业务参数。
            verifyEarlier(row, signature, memory);
            proposed = QuerySql.normalize(
                    terms == null
                            ? models.plan(run, generation, remaining(row), planningQuestion, evidence, rules, memory)
                            : models.plan(
                                    run, generation, remaining(row), planningQuestion, evidence, rules, memory, terms),
                    terms != null);
        } else {
            // 沿用上一轮的商品和口径，在编译每份候选时计算该片段支持的日期。
            proposed = previous;
        }
        switch (proposed.decision()) {
            case "READY" -> {
                // 后续独立语义与输出核对使用已固定的提议。
            }
            case "CLARIFY" -> {
                // 缺少改变业务结果的条件时，暂停在原轮次。
                waitForAnswer(row, proposed.clarification());
                return null;
            }
            case "DENIED" -> throw QueryFailure.denied();
            case "NO_KNOWLEDGE" -> throw QueryFailure.knowledge();
            case "OUT_OF_SCOPE" -> throw new QueryFailure("OUT_OF_SCOPE");
            default -> throw QueryFailure.technical();
        }
        runAccess.requireRules(row, signature);
        DateFollowup dateFollowup = null;
        var timeCompiler = new QuerySql();
        boolean keepsPastDates = timeCompiler.keepsBusinessDay(question);
        boolean changesConfirmedDate =
                clarifiedHistoryDate || !keepsPastDates && timeCompiler.keepsOtherConditions(question);
        if (previous != null && (adjacentDay != null || followsWeek || changesConfirmedDate || keepsPastDates)) {
            // 在当前发起者权限下读取冻结历史轮次的实际血缘。
            var prior = recent.get(recent.size() - 1);
            var usage = runAccess.asActor(row, () -> context.executionUsage(row, signature, prior));
            if (usage == null) {
                // 旧轮次缺少血缘，无法仅凭逻辑字段确定原查询对象。
                throw QueryFailure.knowledge();
            }
            var offset = adjacentDay;
            if (clarifiedAdjacentDate && !timeCompiler.requestsDate(row.supplement())) {
                // 用户只选了日期列，保留原追问的相邻日偏移。
                offset = context.adjacentDay(row.question());
            }
            dateFollowup = new DateFollowup(previous, offset, followsWeek, changesConfirmedDate, usage);
        }
        CheckedPlan checked;
        try {
            // 先尝试按当前观测结构核验生成参数。
            checked = compile(scope, proposed, dateFollowup, memory, question, row, terms, signature);
        } catch (QueryFailure invalid) {
            // 仅明确的参数类型不合适才允许在原轮次修订。
            if (!generated || !invalid.parameterMismatch()) {
                // 已验证历史上的日期追问无需再次生成业务条件。
                throw invalid;
            }
            // 新任务使用已冻结的修正节点，旧快照依原生成修订恢复同一个检查点。
            long repair = frozen.repairRevision() == null ? generation : frozen.repairRevision();
            if (repair == 0) {
                // 缺少独立修正配置时不替用户改用其他节点的模型。
                throw QueryFailure.modelUnavailable();
            }
            verifyHistory(row, signature, memory);
            var revised = models.repairParameter(
                    run, repair, remaining(row), planningQuestion, evidence, rules, proposed, memory);
            usedRepair = repair;
            runAccess.requireRules(row, signature);
            checked =
                    compile(scope, QuerySql.normalize(revised, terms != null), dateFollowup, memory, question, row,
                            terms, signature);
        }
        if (checked == null) {
            // 日期或物理表有歧义时已暂停本轮。
            return null;
        }
        var plan = checked.plan();
        var basis = checked.basis();
        var compiled = checked.compiled();
        if (followsWeek) {
            // 核验用户原话之后，再把实际编译出的自然周范围提供给独立复核。
            question = withBusinessDate(
                    question, plan.time().start() + " 至 " + plan.time().end() + "，按日查询");
        } else if (dateFollowup != null
                && plan.time() != null
                && (!dateFollowup.changesConfirmedDate() || dateFollowup.adjacentDay() != null)) {
            // 只有由历史执行或服务端计算的时间才能标为已确认。
            var time = plan.time();
            String range = time.start().equals(time.end()) ? time.start() : time.start() + " 至 " + time.end();
            question = withBusinessDate(question, range);
        } else if (requestedDay != null) {
            // 复核使用独立解析的用户日期，错填的草案值不会成为确认提示。
            question = withBusinessDate(question, requestedDay.toString());
        }
        if (namedPlan != null) {
            // 用户原话和发布依据的商品映射一起提交独立语义与输出核对。
            String confirmed = question + "\n发布依据已确认商品为 " + named.size() + " " + named.name() + "，商品编码 " + named.code()
                    + "。本题查询该规格的可售库存数量。";
            if (confirmed.length() <= 1210) {
                // 附加发布事实仍需满足独立复核请求的长度限制。
                question = confirmed;
            }
        }
        output.checkProjection(rules, basis, compiled.projection());
        output.checkDisclosure(rules, basis.binding().title(), compiled.scope());
        updateStage(row, "CHECKING");
        long semantic = frozen.semanticRevision();
        var selected = List.of(basis.snippet());
        verifyEarlier(row, signature, memory);
        var semanticDecision = checked.selection() == null
                ? models.check(run, "semantic", semantic, remaining(row), question, selected, rules, plan, memory)
                : models.checkSelection(
                        run, semantic, remaining(row), question, selected, rules, plan, memory, checked.selection());
        if (!"ALLOW".equals(semanticDecision)) {
            throw QueryFailure.knowledge();
        }
        if (outputRevision != null) {
            // 有输出限制时才发起独立披露核对。
            verifyHistory(row, signature, memory);
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
                            memory))) {
                throw QueryFailure.denied();
            }
        }

        var revisions = new ArrayList<String>();
        if (history.modelRevision() != null) {
            // 仅把实际采用的摘要模型修订写入这轮执行依据。
            revisions.add(Ids.text(history.modelRevision()));
        }
        if (frozen.intentRevision() != null) {
            // 新版请求先完成了独立问题理解，将其实际修订纳入同轮审计。
            revisions.add(Ids.text(frozen.intentRevision()));
        }
        if (retrieval.modelRevision() != null) {
            // 审计只登记实际采用的候选次序对应的固定修订。
            revisions.add(Ids.text(retrieval.modelRevision()));
        }
        if (generated) {
            // 这轮确实调用了规划节点，审计记录应包含实际模型版本。
            revisions.add(Ids.text(generation));
        }
        if (usedRepair != null) {
            // 修正使用独立修订，记录实际网络调用所用配置。
            revisions.add(Ids.text(usedRepair));
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
        if (checked.usesHistoricalScope()) {
            // 实际沿用上轮计划或日期时登记确切轮次。
            contextIds.add(recent.get(recent.size() - 1).requestId());
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
        return new PreparedPlan(basis, compiled, reasoning, usage);
    }

    /**
     * 只从仍受当前权限约束的正式索引取证据，模型回包后再次核对。
     */
    private RetrievedBasis retrieveBasis(
            Request row,
            String signature,
            String question,
            RunInput frozen,
            QueryModels.Run run,
            QueryProtocol.Memory memory) {
        String search = retrievalText(question, memory);
        QueryProtocol.Intent intent = null;
        if (frozen.intentRevision() != null) {
            // 问题和历史上下文仅在本轮申请人的规则仍有效时发给已分配的理解模型。
            verifyHistory(row, signature, memory);
            try {
                // 保留原问题与已核验的上轮检索上下文，再补充理解节点整理的表达。
                intent = readIntent(row, frozen.intentRevision(), run, memory, signature);
                var understood = intent.retrievalQuery();
                if (!question.equals(understood)) {
                    // 原样返回时已有本轮原话，避免重复加入同一句。
                    search += "\n" + understood;
                }
            } finally {
                // 外部理解期间取消或撤权时，不进入后续知识检索。
                runAccess.requireRules(row, signature);
            }
        }
        var indexes = runAccess.asActor(row, () -> {
            // 只为可信原发起者取得当前仍有权使用的索引。
            runAccess.requireRules(row, signature);
            context.verify(row, signature, memory);
            return corpus.indexes(row.sourceId(), frozen.bindings(), remaining(row));
        });
        if (indexes.isEmpty()) {
            // 正式知识范围为空时按知识不足结束。
            throw QueryFailure.knowledge();
        }
        List<QueryProtocol.Hit> hits;
        try {
            // 调用属于同一运行的正式召回节点。
            hits = models.retrieve(run, search, indexes, remaining(row));
        } finally {
            // 即使上游返回失败，也先落实在等待期间发生的取消与撤权。
            runAccess.requireRules(row, signature);
        }
        var ranked = rankBasis(row, signature, search, frozen, run, memory, hits);
        return new RetrievedBasis(ranked.bases(), ranked.modelRevision(), intent);
    }

    /**
     * 原问复用自己的回执，补充单独识别并按真实来源标记。
     */
    private QueryProtocol.Intent readIntent(
            Request row, long revision, QueryModels.Run run, QueryProtocol.Memory memory, String signature) {
        var original = models.understand(
                new QueryModels.Run(run.id(), run.version(), false), revision, remaining(row), row.question(), memory);
        if (row.supplement() == null) {
            // 单轮问题继续使用原始回执。
            return original.conditions() == null
                    ? original
                    : new QueryProtocol.Intent(
                            original.retrievalQuery(),
                            original.conditions().stream()
                                    .map(term -> term.from("QUESTION"))
                                    .toList(),
                            original.ambiguous());
        }
        runAccess.requireRules(row, signature);
        var amendment = models.understandSupplement(run, revision, remaining(row), row.supplement(), memory);
        if (original.conditions() == null && amendment.conditions() == null) {
            // 同轮次的旧回执可以只有检索表述，保留其原始计划输入形状。
            return amendment;
        }
        var terms = new ArrayList<QueryProtocol.RequestedTerm>();
        if (original.conditions() != null) {
            // 旧回执可只有检索文字，完整回执保留原问条件。
            original.conditions().forEach(term -> terms.add(term.from("QUESTION")));
        }
        if (amendment.conditions() != null) {
            // 补充的条件仅以用户实际补充为依据。
            amendment.conditions().forEach(term -> terms.add(term.from("SUPPLEMENT")));
        }
        return new QueryProtocol.Intent(amendment.retrievalQuery(), terms, amendment.ambiguous());
    }

    /**
     * 在调用规划模型前固定指定表的依据范围，首轮漏召回时定向补查。
     */
    private Scope planningScope(
            Request row,
            String signature,
            RunInput frozen,
            QueryProtocol.Memory memory,
            List<QueryCorpus.Basis> recalled) {
        if (!QueryTableSelection.mayMentionTable(row.question(), row.supplement())) {
            // 普通问法保留原召回和规划路径。
            return new Scope(recalled, Set.of());
        }
        var matching = QueryTableSelection.recalledScope(recalled, row.question(), row.supplement());
        if (matching.isPresent()) {
            // 已有指定表的候选时，规划只使用这些候选。
            return matching.orElseThrow();
        }
        return runAccess.asActor(row, () -> {
            // 补查仍受本轮发布、原发起者和当前权限约束。
            runAccess.requireRules(row, signature);
            context.verify(row, signature, memory);
            var scope =
                    corpus.targetedScope(row.sourceId(), frozen.bindings(), recalled, row.question(), row.supplement());
            runAccess.requireRules(row, signature);
            return scope;
        });
    }

    /** 外发前及回包后都从当前授权下的发布版本回看候选正文。 */
    private List<QueryCorpus.Basis> verifiedBasis(Request row, String signature, QueryProtocol.Memory memory,
            List<QueryProtocol.Hit> hits) {
        return runAccess.asActor(row, () -> {
            // 复核查询权限、检索表述依赖的历史，以及每条片段的确切身份。
            runAccess.requireRules(row, signature);
            context.verify(row, signature, memory);
            return hits.stream().map(hit -> corpus.basis(row.sourceId(), hit)).toList();
        });
    }

    /** 复用已有召回集合，只在多候选且配置可用时尝试排序。 */
    private RetrievedBasis rankBasis(Request row, String signature, String search, RunInput frozen, QueryModels.Run run,
            QueryProtocol.Memory memory, List<QueryProtocol.Hit> hits) {
        var bases = verifiedBasis(row, signature, memory, hits);
        Long revision = frozen.rerankRevision();
        if (revision == null || revision == 0L || hits.size() < 2) {
            // 旧快照、单候选和未配置模型都沿用现有召回路径。
            return new RetrievedBasis(bases, null);
        }
        var decision = run.clarified() ? frozen.clarifiedRerank() : frozen.rerank();
        if (decision == null) {
            RerankOrder order = null;
            int seconds = remaining(row);
            if (seconds >= MIN_OPTIONAL_BUDGET_SECONDS) {
                try {
                    // 仅向第三方提交已核验正文以及本轮相同检索表述。
                    order = models.rerank(run, revision, seconds, search,
                            bases.stream().map(QueryCorpus.Basis::text).toList());
                } catch (QueryFailure | BizIllegalException unavailable) {
                    // 外部不可用时不重新发出请求，使用原始候选次序。
                    log.info("问数候选重排回退，request={}", row.id());
                }
                bases = verifiedBasis(row, signature, memory, hits);
            }
            decision = QueryReranking.decide(hits, order);
            var path = run.clarified() ? "$.clarifiedRerank" : "$.rerank";
            if (requests.freezeRerank(row.id(), row.runVersion(), path, json.write(decision)) != 1) {
                // 结果和回退都需要固定，迟到结果不能改变已发出的规划输入。
                throw QueryFailure.technical();
            }
        }
        return new RetrievedBasis(QueryReranking.restore(hits, bases, decision), decision.applied() ? revision : null);
    }

    /** 下游读取实际获准的证据次序及采用的模型修订。 */
    private record RetrievedBasis(List<QueryCorpus.Basis> bases, Long modelRevision, QueryProtocol.Intent intent) {
        /**
         * 排序阶段只负责核对候选，不改写问题理解回执。
         */
        private RetrievedBasis(List<QueryCorpus.Basis> bases, Long modelRevision) {
            this(bases, modelRevision, null);
        }
    }

    /**
     * 执行之前在独立事务内记录唯一执行许可，源端只接收已核准的只读计划。
     */
    private QueryDataVO execute(Request row, String signature, PreparedPlan prepared) throws SQLException {
        var basis = prepared.basis();
        var compiled = prepared.compiled();
        long binding = basis.binding().id();
        var executionFingerprint = fingerprints.command(
                "query.execute", Ids.text(row.sourceId()), List.of(compiled.sql(), compiled.parameters()));
        running.put(row.id(), new Running(row, binding, signature));
        return jdbc.execute(
                basis.source(),
                row.id(),
                compiled,
                () -> remaining(row),
                () -> tx.executeWithoutResult(status -> {
                    // 先锁定原用户，登记执行许可后才向源端发送。
                    guard.shared();
                    users.lock(row.userId());
                    runAccess.verifyBinding(
                            row, binding, signature, basis.source().configurationId());
                    remaining(row);
                    if (requests.hasUnresolvedExecution(row.userId(), row.sourceId(), executionFingerprint, row.id())) {
                        // 不为另一条原文不同、实际执行相同的轮次派发 SQL。
                        throw QueryFailure.technical();
                    }
                    if (requests.permit(new Permit(
                                    row.id(),
                                    row.runVersion(),
                                    UUID.randomUUID().toString(),
                                    binding,
                                    signature,
                                    json.write(prepared.reasoning()),
                                    executionFingerprint,
                                    json.write(prepared.usage())))
                            != 1) {
                        throw new QueryFailure("USER_CANCELLED");
                    }
                }));
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
     * 只在同一发布中查找候选。计划指定的指标存在时，只匹配其确切依据。
     */
    private List<QueryCorpus.Basis> matchingBases(Scope scope, QueryProtocol.Plan plan) {
        if (plan == null || !"READY".equals(plan.decision()) || plan.fields() == null) {
            // 缺失候选不能携带到业务数据库。
            throw QueryFailure.knowledge();
        }
        var sameBinding = scope.bases().stream()
                .filter(value -> Ids.text(value.binding().id()).equals(plan.binding()))
                .toList();
        if (!scope.requiredObjects().isEmpty()) {
            // 已提供限定证据后，模型选用其他发布或不在证据内的表属于规划错误。
            var tables = sameBinding.stream()
                    .flatMap(basis -> basis.tables().keySet().stream())
                    .collect(Collectors.toSet());
            if (sameBinding.isEmpty()
                    || plan.fields().stream().anyMatch(field -> field != null && !tables.contains(field.table()))
                    || plan.filters() != null
                            && plan.filters().stream()
                                    .anyMatch(filter -> filter != null
                                            && filter.field() != null
                                            && !tables.contains(filter.field().table()))
                    || plan.time() != null
                            && plan.time().field() != null
                            && !tables.contains(plan.time().field().table())) {
                // 不能把错表提议作为发布知识缺口上报。
                throw QueryFailure.planTargetMismatch();
            }
        }
        var named = !"VALUES".equals(plan.operation())
                        && plan.metric() != null
                        && !plan.metric().isBlank()
                ? sameBinding.stream()
                        .filter(basis -> basis.facts().values().stream()
                                .anyMatch(
                                        fact -> "METRIC".equals(fact.getTarget().getKind())
                                                && plan.metric().equals(fact.getLabel())))
                        .toList()
                : List.<QueryCorpus.Basis>of();
        var matching = (named.isEmpty() ? sameBinding : named)
                .stream()
                        .filter(value -> plan.fields().stream().allMatch(value.labels()::containsKey))
                        .toList();
        if (matching.isEmpty()) {
            // 找不到本发布支持指定投影的候选时，不能换用另一发布。
            throw QueryFailure.knowledge();
        }
        return matching;
    }

    /**
     * 分别解析并完整编译同版候选，日期或物理身份有歧义时在原轮次澄清。
     */
    private CheckedPlan compile(
            Scope scope,
            QueryProtocol.Plan plan,
            DateFollowup followup,
            QueryProtocol.Memory memory,
            String question,
            Request row,
            List<QueryProtocol.RequestedTerm> terms,
            String signature) {
        var compiler = new QuerySql();
        var comparison = terms == null ? null : textComparison(row, signature);
        if (followup != null
                && (followup.previousWeek() || followup.adjacentDay() != null || followup.changesConfirmedDate())) {
            // 在匹配片段前固定原指标，模型给出的新计算字段不能影响候选范围。
            plan = compiler.withHistoricalMetric(plan, followup.previous());
        }
        var candidates = matchingBases(scope, plan);
        QueryRequestedScope requested = null;
        var exclusions = new ArrayList<QueryProtocol.CandidateExclusion>();
        if (terms != null) {
            // 按原计划的真实投影核对有哪些候选提供同一物理来源的字段映射。
            var projection = compiler.withTime(plan, List.of(), null);
            var relevant = scope.bases().stream()
                    .filter(basis -> candidates.stream().anyMatch(anchor -> samePlanInputs(anchor, basis, projection)))
                    .toList();
            requested =
                    QueryRequestedScope.resolve(relevant, plan, terms, row.question(), row.supplement(), comparison);
            if (requested == null) {
                waitForAnswer(row, "你想包含或排除哪些业务范围？");
                return null;
            }
            // 用户原话所绑定的列由服务端负责。未覆盖的模型排除仍须拒绝。
            plan = QuerySql.normalize(requested.comparisonPlan(compiler, plan));
        }
        var day = businessDay(memory);
        var requestedDay = QueryDates.requestedDay(row.question(), row.supplement(), day);
        boolean hasHistory = !memory.recent().isEmpty() || !memory.earlier().isEmpty();
        boolean needsHistoricalDate = false;
        QueryFailure parameterFailure = null;
        QueryFailure unsupported = null;
        var complete = new ArrayList<CheckedPlan>();
        var anchors = new ArrayList<PlanAnchor>();
        for (var candidate : candidates) {
            // 完整核对时间、筛选、指标、关联和参数来源，每次只用一份片段的依据。
            try {
                var basis = candidate;
                if (followup != null) {
                    // 使用上一轮的关联修订编译，候选缺失原关联时继续寻找可沿用的片段。
                    basis = followup.usage().historicalBasis(candidate, followup.previous());
                    if (basis == null) {
                        addExclusion(requested, candidate, exclusions, "HISTORICAL_SCOPE_MISMATCH");
                        continue;
                    }
                }
                if (followup == null) {
                    // 排除不会进入实际查询的模型日期，再以真实物理输入比较其他发布。
                    var dated = compiler.dateInputs(basis, plan, question, hasHistory, requestedDay);
                    var inputs = requested == null ? compiler.independentBindingFilters(basis, dated, question) : dated;
                    if (samePlanInputs(basis, basis, inputs)) {
                        // 后备发布从同一输入独立解析自己确认的默认日期和固定规则。
                        anchors.add(new PlanAnchor(basis, inputs));
                    }
                }
                if (excluded(compiler, requested, basis, plan, exclusions, comparison)) {
                    continue;
                }
                var resolved = followup == null
                        ? compiler.resolveTime(basis, plan, day, question, hasHistory, requestedDay)
                        : resolveHistoricalDate(compiler, basis, plan, followup, day, question, requestedDay);
                if (resolved == null) {
                    // 只有原对象和关联已经核实，才把历史计划缺失归到日期澄清。
                    needsHistoricalDate |= followup != null;
                    addExclusion(requested, basis, exclusions, "DATE_UNVERIFIABLE");
                    continue;
                }
                // 语义复核、历史血缘与实际 SQL 使用同一份已合并指标条件的计划。
                resolved = compiler.applyMetricFilters(
                        basis, requested == null ? resolved : requested.apply(compiler, basis, resolved));
                var compiled = jdbc.compile(basis, resolved);
                if (requestedDay != null && !compiled.parameters().contains(requestedDay)) {
                    // 已确认日必须以日期类型进入实际 SQL，不能只出现在模型说明中。
                    addExclusion(requested, basis, exclusions, "USER_DATE_NOT_BOUND", resolved, compiled);
                    continue;
                }
                if (followup != null && !followup.usage().sameHistoricalScope(compiled.usage())) {
                    // 再次核对 SQL 实际使用的表和关联修订，保证日期追问仍在原执行范围内。
                    addExclusion(requested, basis, exclusions, "HISTORICAL_SCOPE_MISMATCH", resolved, compiled);
                    continue;
                }
                var parameterUse = compiler.checkProductCodes(
                        basis, resolved, question, memory, requested == null ? List.of() : requested.conditions());
                complete.add(new CheckedPlan(resolved, basis, compiled, followup != null, parameterUse, null));
            } catch (QueryFailure failure) {
                if ("OUT_OF_SCOPE".equals(failure.state())) {
                    // 视图等不支持的对象不能作为完整依据，也不会遮挡可编译的普通表。
                    unsupported = failure;
                    addExclusion(requested, candidate, exclusions, failure.state());
                    continue;
                }
                if (!"KNOWLEDGE_INSUFFICIENT".equals(failure.state()) || failure.diagnostic() != null) {
                    // 权限、预算和技术错误保持原处理。
                    throw failure;
                }
                if (failure.parameterMismatch() && parameterFailure == null) {
                    // 没有其他候选可以执行时，保留原有的一次参数修正入口。
                    parameterFailure = failure;
                }
                addExclusion(requested, candidate, exclusions, failure);
            }
        }
        if (complete.isEmpty()) {
            if (followup == null && parameterFailure == null && !anchors.isEmpty()) {
                // 模型选中的发布不适用时，只有原物理输入一致才允许使用其他召回口径。
                var anchor = anchors.get(0);
                if (anchors.stream()
                        .allMatch(item -> anchor.plan().equals(item.plan())
                                && samePlanInputs(anchor.basis(), item.basis(), anchor.plan()))) {
                    var alternatives = compileOtherBindings(
                            scope, anchor, requestedDay, question, memory, requested, exclusions, comparison);
                    if (!alternatives.isEmpty()) {
                        // 多份等价证据可以复用，实际路径或结果口径不同则停止派发。
                        var fallback = alternatives.get(0);
                        if (alternatives.stream()
                                .anyMatch(other -> !sameAnswerShape(fallback, other, comparison)
                                        || !sameJoinPath(fallback.compiled(), other.compiled()))) {
                            if (requested != null) {
                                waitForAnswer(row, "有多个已发布的统计口径，你想查哪个范围？");
                                return null;
                            }
                            throw QueryFailure.knowledge();
                        }
                        return withSelection(fallback, requested, exclusions, scope, alternatives);
                    }
                }
            }
            // 无完整依据时保留不支持对象及原参数修正入口。
            if (unsupported != null) {
                throw unsupported;
            }
            if (followup != null
                    && parameterFailure == null
                    && needsHistoricalDate
                    && (followup.previousWeek() || followup.adjacentDay() != null || followup.changesConfirmedDate())) {
                // 原表仍可核实时，单独澄清尚不能确定的日期口径。
                waitForAnswer(row, followup.previousWeek() ? PREVIOUS_WEEK_CLARIFICATION : ADJACENT_DATE_CLARIFICATION);
                return null;
            }
            throw parameterFailure == null ? QueryFailure.knowledge() : parameterFailure;
        }
        var selected = QueryTableSelection.select(
                complete.stream().map(CheckedPlan::compiled).toList(), scope.requiredObjects());
        if (selected.isEmpty()) {
            // 所有完整候选都参与比较，排序不能消解物理表歧义。
            waitForAnswer(row, TABLE_CLARIFICATION);
            return null;
        }
        var chosen = complete.get(selected.getAsInt());
        var viable = new ArrayList<CheckedPlan>();
        for (var candidate : complete) {
            // 同一发布的不同片段也可能是不同筛选或计量口径。
            if (candidate.compiled().usage().objects().stream()
                    .map(QueryUsage.ObjectRef::objectId)
                    .collect(Collectors.toSet())
                    .containsAll(scope.requiredObjects())) {
                viable.add(candidate);
            } else {
                addExclusion(
                        requested,
                        candidate.basis(),
                        exclusions,
                        "USER_TABLE_NOT_READ",
                        candidate.plan(),
                        candidate.compiled());
            }
        }
        if (requested != null && viable.stream().anyMatch(other -> !sameAnswerShape(chosen, other, comparison)
                || !sameJoinPath(chosen.compiled(), other.compiled()))) {
            // 排名不能在同一发布内掩盖已编译口径的差异。
            waitForAnswer(row, "有多个已发布的统计口径，你想查哪个范围？");
            return null;
        }
        if (followup == null) {
            // 只要实际投影、筛选和日期引用同一批源字段，换一个中间表也属于潜在冲突。
            var anchor = anchors.stream()
                    .filter(item -> item.basis() == chosen.basis())
                    .findFirst()
                    .orElse(null);
            if (anchor != null) {
                // 同一模型输入不带入首选发布的固定值，防止漏判支付条件等冲突。
                var alternatives = compileOtherBindings(
                        scope, anchor, requestedDay, question, memory, requested, exclusions, comparison);
                if (alternatives.stream()
                        .anyMatch(other -> !sameAnswerShape(chosen, other, comparison)
                                || !sameJoinPath(chosen.compiled(), other.compiled()))) {
                    if (requested != null) {
                        waitForAnswer(row, "有多个已发布的统计口径，你想查哪个范围？");
                        return null;
                    }
                    throw QueryFailure.knowledge();
                }
                viable.addAll(alternatives);
            }
        }
        return withSelection(chosen, requested, exclusions, scope, viable);
    }

    /**
     * 独立编译本轮其他获准发布，保留原物理输入、指标名称、筛选和日期，不拼接证据。
     */
    private List<CheckedPlan> compileOtherBindings(
            Scope scope,
            PlanAnchor anchor,
            LocalDate requestedDay,
            String question,
            QueryProtocol.Memory memory,
            QueryRequestedScope requested,
            List<QueryProtocol.CandidateExclusion> exclusions,
            ValueComparison comparison) {
        var plan = anchor.plan();
        var compiler = new QuerySql();
        var alternatives = new ArrayList<CheckedPlan>();
        var day = businessDay(memory);
        boolean hasHistory = !memory.recent().isEmpty() || !memory.earlier().isEmpty();
        var label = "VALUES".equals(plan.operation()) ? null : comparisonMetric(compiler, anchor);
        for (var basis : scope.bases()) {
            // 先以已确认的源字段身份排除同名异表，查询连接补入的表不参与输入身份比较。
            var binding = Ids.text(basis.binding().id());
            if (binding.equals(plan.binding())) {
                continue;
            }
            if (!samePlanInputs(anchor.basis(), basis, plan)) {
                // 不同物理输入未用于此轮查询。
                addExclusion(requested, basis, exclusions, "PHYSICAL_INPUT_MISMATCH");
                continue;
            }
            var sibling = new QueryProtocol.Plan(
                    plan.decision(),
                    plan.clarification(),
                    binding,
                    plan.operation(),
                    plan.metric(),
                    plan.unit(),
                    plan.fields(),
                    plan.filters(),
                    plan.time());
            try {
                // 计算公式和源字段保持一致，后备指标需在原问题时间锚点上独立解析自己的业务日期。
                if (!"VALUES".equals(plan.operation())
                        && (label == null
                                || !label.equals(compiler.metric(basis, sibling).getLabel()))) {
                    addExclusion(requested, basis, exclusions, "METRIC_MISMATCH");
                    continue;
                }
                if (excluded(compiler, requested, basis, sibling, exclusions, comparison)) {
                    continue;
                }
                var timed = compiler.resolveTime(basis, sibling, day, question, hasHistory, requestedDay);
                var resolved = compiler.applyMetricFilters(
                        basis, requested == null ? timed : requested.apply(compiler, basis, timed));
                var other = jdbc.compile(basis, resolved);
                var objects = other.usage().objects().stream()
                        .map(QueryUsage.ObjectRef::objectId)
                        .collect(Collectors.toSet());
                if (!objects.containsAll(scope.requiredObjects())
                        || requestedDay != null && !other.parameters().contains(requestedDay)) {
                    // 明确指定的源表和业务日期必须真正进入 SQL。
                    var missing = !objects.containsAll(scope.requiredObjects())
                            ? "USER_TABLE_NOT_READ"
                            : "USER_DATE_NOT_BOUND";
                    addExclusion(requested, basis, exclusions, missing, resolved, other);
                    continue;
                }
                var parameterUse = compiler.checkProductCodes(
                        basis, resolved, question, memory, requested == null ? List.of() : requested.conditions());
                alternatives.add(new CheckedPlan(resolved, basis, other, false, parameterUse, null));
            } catch (QueryFailure failure) {
                if ("OUT_OF_SCOPE".equals(failure.state())
                        || "KNOWLEDGE_INSUFFICIENT".equals(failure.state()) && failure.diagnostic() == null) {
                    // 证据不全或条件不适用的片段，不能制造实际关联冲突。
                    addExclusion(requested, basis, exclusions, failure);
                    continue;
                }
                throw failure;
            }
        }
        return alternatives;
    }

    /**
     * 回退固定已有确认的指标名称，公式由每个候选以自己的发布证据核对。
     */
    private String comparisonMetric(QuerySql compiler, PlanAnchor anchor) {
        var plan = anchor.plan();
        try {
            return compiler.metric(anchor.basis(), plan).getLabel();
        } catch (QueryFailure incomplete) {
            // 首选公式不可用时，仍能从其正式证据核对计划指定的指标名字。
            var named = plan.metric();
            boolean known = named != null
                    && !named.isBlank()
                    && anchor.basis().facts().values().stream()
                            .anyMatch(fact ->
                                    "METRIC".equals(fact.getTarget().getKind()) && named.equals(fact.getLabel()));
            return known ? named : null;
        }
    }

    /**
     * 在编译各发布前使用本轮相同的条件核对固定范围。
     */
    private boolean excluded(
            QuerySql compiler,
            QueryRequestedScope requested,
            Basis basis,
            QueryProtocol.Plan plan,
            List<QueryProtocol.CandidateExclusion> exclusions,
            ValueComparison comparison) {
        var reason = requested == null ? null : requested.exclusion(compiler, basis, plan, comparison);
        if (reason == null) {
            return false;
        }
        exclusions.add(reason);
        return true;
    }

    /**
     * 保留候选实际无法采用的有限原因，不向模型发异常或 SQL。
     */
    private void addExclusion(
            QueryRequestedScope requested,
            Basis basis,
            List<QueryProtocol.CandidateExclusion> exclusions,
            String reason) {
        addExclusion(requested, basis, exclusions, reason, null, null);
    }

    /**
     * 两条选路共用实际编译失败证据，公开状态继续使用统一错误协议。
     */
    private void addExclusion(
            QueryRequestedScope requested,
            Basis basis,
            List<QueryProtocol.CandidateExclusion> exclusions,
            QueryFailure failure) {
        var assessment = failure.joinAssessment();
        if (requested != null && assessment != null) {
            // 关联未编译成功，记录已核验的键和判断日期供独立复查。
            exclusions.add(new QueryProtocol.CandidateExclusion(
                    Ids.text(basis.binding().id()),
                    basis.ordinal(),
                    assessment.reason(),
                    List.of(),
                    QueryRequestedScope.candidateEvidence(basis, null, null, assessment),
                    null));
        } else {
            // 没有可核对的关联轨迹时保留原本的排除理由。
            addExclusion(requested, basis, exclusions, failure.state());
        }
    }

    /**
     * 对已编译候选附上可实际核对的日期、筛选和读取范围。
     */
    private void addExclusion(
            QueryRequestedScope requested,
            Basis basis,
            List<QueryProtocol.CandidateExclusion> exclusions,
            String reason,
            QueryProtocol.Plan plan,
            QuerySql.Compiled compiled) {
        if (requested != null) {
            // 旧回执继续使用已有独立复核输入形状。
            exclusions.add(QueryRequestedScope.excluded(basis, reason, List.of(), plan, compiled));
        }
    }

    /**
     * 交付给独立语义节点的实际范围和排除理由保持和选路一致。
     */
    private CheckedPlan withSelection(
            CheckedPlan chosen,
            QueryRequestedScope requested,
            List<QueryProtocol.CandidateExclusion> exclusions,
            Scope scope,
            List<CheckedPlan> viable) {
        if (requested == null) {
            return chosen;
        }
        for (var candidate : scope.bases()) {
            // 同一已核验范围内的每段未选片段都有身份、理由及证据。
            if (sameFragment(chosen.basis(), candidate)
                    || exclusions.stream()
                            .anyMatch(reason -> reason.binding()
                                            .equals(Ids.text(candidate.binding().id()))
                                    && reason.ordinal() == candidate.ordinal())) {
                continue;
            }
            var equivalent = viable.stream()
                    .filter(item -> sameFragment(item.basis(), candidate))
                    .findFirst();
            if (equivalent.isPresent()) {
                var item = equivalent.orElseThrow();
                addExclusion(requested, candidate, exclusions, "EQUIVALENT_PLAN", item.plan(), item.compiled());
            } else {
                // 匹配阶段或已沿用的历史范围可能已限制此候选的实际输入。
                addExclusion(
                        requested,
                        candidate,
                        exclusions,
                        chosen.usesHistoricalScope() ? "HISTORICAL_SCOPE_MISMATCH" : "PLAN_INPUT_NOT_COMPARABLE");
            }
        }
        var review = new QueryProtocol.SelectionReview(
                requested.terms(),
                requested.conditions(),
                List.copyOf(exclusions),
                QueryRequestedScope.candidateEvidence(chosen.basis(), chosen.plan(), chosen.compiled()));
        return new CheckedPlan(
                chosen.plan(),
                chosen.basis(),
                chosen.compiled(),
                chosen.usesHistoricalScope(),
                chosen.parameterUse(),
                review);
    }

    /**
     * 发布与片段序号共同识别同一候选。
     */
    private boolean sameFragment(Basis first, Basis second) {
        return first.binding().id() == second.binding().id() && first.ordinal() == second.ordinal();
    }

    /**
     * 同轮复用同一物理列的比较结果，外发前后核对原发起人当前资格。
     */
    private ValueComparison textComparison(Request row, String signature) {
        var values = new HashMap<TextComparison, Boolean>();
        return (basis, field, first, second) -> {
            if (Objects.equals(first, second)) {
                // 同一参数无需重复联系源库。
                return true;
            }
            if (!(first instanceof String left) || !(second instanceof String right)) {
                // 数值和日期已经由编译器按 JDBC 类型规范化。
                return false;
            }
            var entry = basis.tables().get(field.table()).entry();
            var key = new TextComparison(
                    entry.id(), entry.generation(), basis.source().configurationId(), field, left, right);
            return values.computeIfAbsent(key, ignored -> {
                runAccess.verifyBinding(
                        row, basis.binding().id(), signature, basis.source().configurationId());
                try {
                    return jdbc.sameTextValue(basis, field, left, right, remaining(row));
                } finally {
                    // 等待源库期间发生撤权时不能继续选路。
                    runAccess.requireRules(row, signature);
                }
            });
        };
    }

    /**
     * 缓存按实际源连接、对象和参数区分，只在当前运行中使用。
     */
    private record TextComparison(
            long object, long generation, long configuration, Field field, String left, String right) {}

    /**
     * 比较实际提问使用的列身份，而非 JOIN 后所有物理表的集合。
     */
    private boolean samePlanInputs(Basis first, Basis second, QueryProtocol.Plan plan) {
        if (plan.filters() == null
                || plan.filters().stream().anyMatch(filter -> filter == null || filter.field() == null)
                || plan.time() != null && plan.time().field() == null) {
            // 不完整的模型提议无法作为跨发布的物理输入依据。
            return false;
        }
        var fields = new ArrayList<>(plan.fields());
        plan.filters().stream().map(Condition::field).forEach(fields::add);
        if (plan.time() != null) {
            // 日期列也属于当前业务范围，不能从另一物理列读取相同日期值来替代。
            fields.add(plan.time().field());
        }
        for (var field : fields) {
            // 只承认证据已确认且来自相同对象世代的确切目录列。
            if (!first.labels().containsKey(field) || !second.labels().containsKey(field)) {
                return false;
            }
            var left = first.tables().get(field.table());
            var right = second.tables().get(field.table());
            if (left == null
                    || right == null
                    || left.entry().id() != right.entry().id()
                    || left.entry().generation() != right.entry().generation()) {
                return false;
            }
            var leftColumn = left.entry().named(field.column());
            var rightColumn = right.entry().named(field.column());
            if (leftColumn == null
                    || rightColumn == null
                    || leftColumn.columnId() != rightColumn.columnId()
                    || leftColumn.revisionId() != rightColumn.revisionId()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 比较实际筛选、日期列、结果和单位，发布中的“元”与“万元”是不同口径。
     */
    private boolean sameAnswerShape(CheckedPlan first, CheckedPlan second, ValueComparison comparison) {
        var left = first.compiled();
        var right = second.compiled();
        if (!left.columns().equals(right.columns())
                || !left.projection().equals(right.projection())
                || !Objects.equals(left.unit(), right.unit())
                || left.verifiesUnit() != right.verifiesUnit()) {
            return false;
        }
        var leftFilters = QuerySql.boundFilters(first.basis(), first.plan());
        var rightFilters = QuerySql.boundFilters(second.basis(), second.plan());
        if (comparison == null) {
            // 旧回执沿用既有等价性规则。
            return left.parameters().equals(right.parameters()) && leftFilters.equals(rightFilters);
        }
        return Objects.equals(
                        comparisonTime(first.plan().time()),
                        comparisonTime(second.plan().time()))
                && containsFilters(first.basis(), leftFilters, rightFilters, comparison)
                && containsFilters(second.basis(), rightFilters, leftFilters, comparison);
    }

    /**
     * 比较日期实际范围，展示用默认标记不改变筛选含义。
     */
    private QueryProtocol.TimeSelection comparisonTime(QueryProtocol.TimeSelection time) {
        return time == null
                ? null
                : new QueryProtocol.TimeSelection(time.field(), time.mode(), time.start(), time.end(), null);
    }

    /**
     * 同列同方向筛选按真实源列规则比较类型化值。
     */
    private boolean containsFilters(
            Basis basis,
            Set<QuerySql.BoundFilter> required,
            Set<QuerySql.BoundFilter> available,
            ValueComparison comparison) {
        return required.stream().allMatch(first -> available.stream()
                .anyMatch(second -> QuerySql.sameBoundFilter(basis, first, second, comparison)));
    }

    /**
     * 实际连接同时包含所选中间表的物理身份及连接字段对。
     */
    private boolean sameJoinPath(QuerySql.Compiled first, QuerySql.Compiled second) {
        return first.usage().sameObjects(second.usage()) && first.joinKeys().equals(second.joinKeys());
    }

    /**
     * 确定日期变更后，与原轮次的实际筛选合并成完整执行计划。
     */
    private QueryProtocol.Plan resolveHistoricalDate(
            QuerySql compiler,
            QueryCorpus.Basis basis,
            QueryProtocol.Plan plan,
            DateFollowup followup,
            LocalDate day,
            String question,
            LocalDate requestedDay) {
        var previous = followup.previous();
        if (followup.previousWeek()) {
            // 上周使用本轮接受日所在的自然周，其他条件仍来自原轮次。
            var shifted = compiler.followPreviousWeek(basis, previous, day);
            return shifted == null
                    ? null
                    : compiler.historicalConditions(basis, shifted, previous, question, shifted.time());
        }
        if (followup.changesConfirmedDate()) {
            // 模型只提供新条件候选，Java 复核要替换的日期列。
            var proposed = followup.adjacentDay() == null && requestedDay == null
                    ? compiler.resolveTime(basis, plan, day, question, true, null)
                    : plan;
            var changed = compiler.changedHistoricalDate(
                    basis, previous, proposed, question, followup.adjacentDay(), requestedDay);
            return changed == null ? null : compiler.historicalConditions(basis, proposed, previous, question, changed);
        }
        if (followup.adjacentDay() != null) {
            // 沿用原业务日偏移，不让当前草案改变其他筛选。
            var shifted = compiler.followBusinessDay(basis, plan, previous, followup.adjacentDay());
            return shifted == null
                    ? null
                    : compiler.historicalConditions(basis, shifted, previous, question, shifted.time());
        }
        return compiler.historicalConditions(basis, plan, previous, question, null);
    }

    /**
     * 本轮可沿用的历史计划及用户要求的日期移动。
     *
     * @param previous 上一轮已验证的实际计划
     * @param adjacentDay 用户要求的相邻日偏移
     * @param previousWeek 是否按上一个自然周逐日查询
     * @param changesConfirmedDate 是否明确修改日期并仍需沿用原执行范围
     * @param usage 上一轮实际使用的物理对象及筛选列
     */
    private record DateFollowup(
            QueryProtocol.Plan previous,
            Integer adjacentDay,
            boolean previousWeek,
            boolean changesConfirmedDate,
            QueryUsage usage) {}

    /**
     * 澄清和任务恢复都沿用请求接收时的北京时间日期。
     */
    private LocalDate businessDay(QueryProtocol.Memory memory) {
        return Instant.parse(memory.timeAnchor())
                .atZone(ZoneId.of("Asia/Shanghai"))
                .toLocalDate();
    }

    /**
     * 已编译的实际候选进入独立的语义、输出和时序核验。
     */
    private record CheckedPlan(
            QueryProtocol.Plan plan,
            QueryCorpus.Basis basis,
            QuerySql.Compiled compiled,
            boolean usesHistoricalScope,
            QueryUsage.ParameterUse parameterUse,
            QueryProtocol.SelectionReview selection) {}

    /**
     * 模型所选发布中已解析日期和筛选的计划，作为其他发布的物理输入边界。
     */
    private record PlanAnchor(Basis basis, QueryProtocol.Plan plan) {}

    /**
     * 定期检查实际在途执行的当前资格并尝试取消，独立于查询工作线程。
     */
    @Scheduled(fixedDelay = 1000)
    void observe() {
        leases.forEach((id, run) -> requests.renew(id, run));
        running.forEach((id, run) -> {
            // 逐条核验在途查询的实时预算与使用权限。
            try {
                // 任何不再允许的执行交给 JDBC 取消。
                remaining(run.row());
                runAccess.verifyBinding(run.row(), run.binding(), run.signature(), null);
            } catch (RuntimeException cannotContinue) {
                // 未能继续核验时对该轮尝试停止源查询。
                jdbc.cancel(id);
            }
        });
    }

    /**
     * 修复遗留待核实状态与过期结果，不自动重发已派发的 SQL。
     */
    @Scheduled(fixedDelay = 15000)
    void expire() {
        requests.expire();
        requests.expireResults();
        explanations.expire();
    }

    /**
     * 首次解析固定模型修订与对话证据，随后只复用该快照。
     */
    private RunInput prepare(Request row, String signature, boolean noRules) {
        if (row.runInput() != null) {
            // 恢复使用已固化输入，不重新选择目录、上下文和模型。
            var saved = json.read(row.runInput(), RunInput.class);
            if (!signature.equals(saved.signature())) {
                // 旧冻结规则与当前规则不一致时停止恢复。
                throw QueryFailure.denied();
            }
            return saved;
        }
        return runAccess.asActor(row, () -> {
            // 首次派发只在原主体授权下固定依赖与证据。
            runAccess.requireRules(row, signature);
            var bindings = corpus.bindings(row.sourceId());
            if (bindings.isEmpty()) {
                // 没有生效知识时确立来源级缺口，不调用模型猜测原因。
                throw QueryFailure.noPublishedKnowledge();
            }
            var memory = memory(row, signature);
            var frozen = new RunInput(
                    signature,
                    memory,
                    bindings,
                    models.revision("sql-generate"),
                    models.revision("sql-semantic-check"),
                    noRules ? null : models.revision("output-meaning"),
                    models.repairRevision(),
                    models.revision("intent"),
                    memory.earlier().isEmpty() ? 0L : models.historySummaryRevision(),
                    null,
                    models.rerankRevision(),
                    null,
                    null);
            if (requests.freeze(row.id(), row.runVersion(), json.write(frozen)) != 1) {
                // 只有当前代次可写首次冻结的上下文。
                throw QueryFailure.technical();
            }
            return frozen;
        });
    }

    /** 摘要先落盘再进入理解和规划，恢复及补充沿用同一份上下文。 */
    private PreparedHistory history(Request row, String signature, RunInput frozen, QueryModels.Run run) {
        var memory = frozen.memory();
        var revision = frozen.historySummaryRevision();
        if (revision == null || memory.earlier().isEmpty()) {
            // 旧版冻结轮次及无早期历史的轮次沿用原输入。
            return new PreparedHistory(memory, null);
        }
        SummaryDecision decision = frozen.historySummary();
        if (decision == null) {
            // 同一原输入确定性消除已纠正的旧条件，缺少模型时仍有可用摘要。
            var input = QueryHistorySummary.input(memory);
            decision = QueryHistorySummary.decide(input, null);
            if (revision != 0L && !input.earlier().isEmpty()) {
                decision = selectHistory(row, signature, run, revision, input, decision);
            }
        }
        return new PreparedHistory(QueryHistorySummary.apply(memory, decision), decision.applied() ? revision : null);
    }

    /** 只有仍有权且剩余时间足够时才调用摘要，固定成功或回退结果。 */
    private SummaryDecision selectHistory(Request row, String signature, QueryModels.Run run, long revision,
            QueryProtocol.Memory input, SummaryDecision fallback) {
        int seconds = remaining(row);
        verifyHistory(row, signature, input);
        var selected = fallback;
        if (seconds >= MIN_OPTIONAL_BUDGET_SECONDS) {
            try {
                // 用户补充不改变已登记的摘要输入，仍使用原问题。
                selected = QueryHistorySummary.decide(input,
                        models.summarize(run, revision, seconds, row.question(), input));
            } catch (QueryFailure | BizIllegalException unavailable) {
                // 可选节点超时、停用或回包不确定时保留可追溯的结构化口径。
                log.info("问数历史摘要回退，request={}", row.id());
            }
        }
        // 摘要期间的取消、撤权或知识变化优先于回退和后续派发。
        verifyHistory(row, signature, input);
        if (requests.freezeSummary(row.id(), row.runVersion(), json.write(selected)) != 1) {
            // 失去租约后禁止当前工作者使用尚未固定的输入。
            throw QueryFailure.technical();
        }
        return selected;
    }

    /** 长对话的发布依赖在后续使用摘要前仍须保持可用。 */
    private void verifyEarlier(Request row, String signature, QueryProtocol.Memory memory) {
        if (!memory.earlier().isEmpty()) {
            // 短对话继续沿用现有的检索与权限核对路径。
            verifyHistory(row, signature, memory);
        }
    }

    /** 在可信原发起人下核实轮次归属、当前规则和历史证据。 */
    private void verifyHistory(Request row, String signature, QueryProtocol.Memory memory) {
        runAccess.asActor(row, () -> {
            runAccess.requireRules(row, signature);
            context.verify(row, signature, memory);
            return null;
        });
    }

    /** 本轮供后续节点使用的上下文和实际采用的摘要模型修订。 */
    private record PreparedHistory(QueryProtocol.Memory memory, Long modelRevision) {}

    /**
     * 复验只沿用原问题接受日，继续使用当前发布的知识与当前权限。
     */
    private QueryProtocol.Memory memory(Request row, String signature) {
        if (row.recheckOriginId() == null) {
            // 普通问数可核对同一对话中的确切前文。
            return context.capture(row, signature);
        }
        var original = requests.get(row.recheckOriginId());
        return new QueryProtocol.Memory(
                original.createdAt().toInstant(ZoneOffset.UTC).toString(), List.of(), List.of());
    }

    /**
     * 检索使用有界同源上下文，步骤回执指纹同时绑定原提问和已确认条件。
     */
    private String retrievalText(String question, QueryProtocol.Memory memory) {
        if (memory.recent().isEmpty()) {
            // 没有可采用的前文时只使用当前用户原话。
            return question;
        }
        var prior = memory.recent().get(memory.recent().size() - 1);
        var metric = prior.plan() == null ? "" : Objects.toString(prior.plan().metric(), "");
        return question + "\n同源上轮：" + prior.question() + "\n已确认指标：" + metric;
    }

    /**
     * 已捕获结果可被新调度进程交付，交付前核对当前权限和原快照内容。
     */
    private void finish(Request row) {
        tx.executeWithoutResult(status -> {
            // 交付和解释登记都基于同一受保护的已捕获结果。
            guard.shared();
            var captured = requests.get(row.id());
            if (captured.runVersion() != row.runVersion() || !"FINALIZING".equals(captured.state())) {
                // 禁止用旧租约交付其他代次或尚未捕获的结果。
                throw QueryFailure.technical();
            }
            var settings = json.read(captured.reasoning(), Reasoning.class);
            var rules = runAccess.verifyBinding(
                    row, captured.bindingId(), captured.ruleSignature(), settings.configuration());
            if (!rules.isEmpty()) {
                // 回收旧队列时也须使用当前已核验的限制检查原结果。
                output.checkResult(rules, json.read(captured.result(), QueryDataVO.class), json);
            }
            if (requests.deliver(row.id(), row.runVersion()) != 1) {
                // 原状态仍由台账决定，取消意图和租约竞争不会在这里冒充取消成功。
                throw QueryFailure.technical();
            }
            // 交付已经扣除实际耗时，首次说明读取同一事务内的最新余额。
            explanations.initial(requests.get(row.id()));
        });
    }

    /**
     * 更新可订阅进度，已经取消的轮次无后续生成。
     */
    private void updateStage(Request row, String stage) {
        if (requests.stage(row.id(), row.runVersion(), stage) != 1) {
            // 无法推进当前轮次时停止后续外发。
            throw new QueryFailure("USER_CANCELLED");
        }
    }

    /**
     * 活动预算至少剩下一秒才能进入下一步。
     */
    private int remaining(Request row) {
        long spent = Duration.between(instant(row.processingStartedAt()), Instant.now())
                .toMillis();
        long seconds = (row.remainingMillis() - spent) / 1000;
        if (seconds < 1) {
            // 不足一秒无法再启动下一节点。
            throw QueryFailure.resource();
        }
        return (int) Math.min(120, seconds);
    }

    /**
     * 只在确实已登记执行时保留运行、实际成功或未知记录。
     */
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

    /**
     * 核验完成的计划以及须与执行许可同事务留下的证据。
     */
    private record PreparedPlan(
            QueryCorpus.Basis basis, QuerySql.Compiled compiled, Reasoning reasoning, QueryUsage usage) {}

    /**
     * 独立心跳复核在途许可所用的请求、发布与输出签名。
     */
    private record Running(Request row, long binding, String signature) {}
}
