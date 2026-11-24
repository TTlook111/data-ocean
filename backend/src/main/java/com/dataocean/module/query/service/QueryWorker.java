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
import com.dataocean.module.query.service.QueryProtocol.Reasoning;
import com.dataocean.module.query.service.QueryProtocol.RunInput;
import com.dataocean.module.query.service.QueryProtocol.SummaryDecision;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    /** 可选摘要最多等待两次短请求，余量不足时使用结构化历史。 */
    private static final int MIN_HISTORY_BUDGET_SECONDS = 35;

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
        var relativeDay = QueryDates.singleRelativeDay(question, businessDay(memory));
        Integer adjacentDay = row.supplement() == null ? context.adjacentDay(row.question()) : null;
        boolean asksPreviousWeek = frozen.historySummaryRevision() != null
                && row.supplement() == null && QueryDates.previousWeekFollowup(row.question());
        if ((adjacentDay != null || asksPreviousWeek) && previous == null) {
            // 纯追问没有上文时先向本人求证，不从别的对话或文档示例猜测。
            updateStage(row, "GENERATING");
            waitForAnswer(row, asksPreviousWeek ? "上周你想查什么指标？请补充商品或范围。"
                    : "你想查什么数据？请补充商品或范围，以及要查询的日期。");
            return null;
        }
        Long outputRevision = frozen.outputRevision();
        var bases = retrieveBasis(row, signature, question, frozen, run, memory);
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
                && (relativeDay != null || !QueryDates.mentionsSpecificDate(question))) {
            // 该问法只有已核验的商品、规格及单日范围，不让示例中的其他条件进入计划。
            namedPlan = new QuerySql().namedStock(named, relativeDay);
        }
        long generation = frozen.generationRevision();
        Long usedRepair = null;
        String planningQuestion = relativeDay == null ? question : withBusinessDate(question, relativeDay.toString());
        QueryProtocol.Plan proposed;
        boolean followsWeek = asksPreviousWeek && previous != null && previous.time() != null;
        boolean generated = adjacentDay == null && !followsWeek && namedPlan == null;
        if (namedPlan != null) {
            // 采用正式片段确认的单日查询计划。
            proposed = namedPlan;
        } else if (generated) {
            // 当前问题仍由正常规划节点识别意图和业务参数。
            verifyEarlier(row, signature, memory);
            proposed = QuerySql.normalize(
                    models.plan(run, generation, remaining(row), planningQuestion, evidence, rules, memory));
        } else {
            // 纯日期追问沿用上一轮核验的商品和口径，整体自然周按日计算。
            var basis = selectBasis(bases, previous);
            var compiler = new QuerySql();
            proposed = followsWeek ? compiler.followPreviousWeek(basis, previous, businessDay(memory))
                    : compiler.followBusinessDay(basis, previous, previous, adjacentDay);
            if (proposed == null) {
                // 上轮没有支持本次日期变更的可用口径，保留问题并明确澄清。
                waitForAnswer(row, followsWeek ? "请确认上周需要逐日查询的指标。"
                        : "上一轮没有可作为基准的单个业务日期。请告诉我要查询的日期。");
                return null;
            }
            if (!followsWeek) {
                // 单日路径沿用现有日期提示，自然周先以用户原话核对商品沿用条件。
                question = withBusinessDate(question, proposed.time().start());
            }
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
        CheckedPlan checked;
        try {
            // 先尝试按当前观测结构核验生成参数。
            checked = compile(bases, proposed, previous, memory, question);
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
            checked = compile(bases, QuerySql.normalize(revised), previous, memory, question);
        }
        var plan = checked.plan();
        var basis = checked.basis();
        var compiled = checked.compiled();
        if (followsWeek) {
            // 核验用户原话之后，再把实际编译出的自然周范围提供给独立复核。
            question = withBusinessDate(question, plan.time().start() + " 至 " + plan.time().end() + "，按日查询");
        } else if (checked.inheritedDay()) {
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
        if (!"ALLOW"
                .equals(models.check(
                        run, "semantic", semantic, remaining(row), question, selected, rules, plan, memory))) {
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
        if (adjacentDay != null || followsWeek || checked.inheritedDay()) {
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
    private List<QueryCorpus.Basis> retrieveBasis(
            Request row, String signature, String question, RunInput frozen, QueryModels.Run run,
            QueryProtocol.Memory memory) {
        String search = retrievalText(question, memory);
        if (frozen.intentRevision() != null) {
            // 问题和历史上下文仅在本轮申请人的规则仍有效时发给已分配的理解模型。
            verifyHistory(row, signature, memory);
            try {
                // 保留原问题与已核验的上轮检索上下文，再补充理解节点整理的表达。
                String understood =
                        models.understand(run, frozen.intentRevision(), remaining(row), question, memory);
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
        if (hits.isEmpty()) {
            // 没有实际命中时无法为计划提供正式依据。
            throw QueryFailure.knowledge();
        }
        return runAccess.asActor(row, () -> {
            // 按当前来源权限回看刚才实际命中的正式片段。
            runAccess.requireRules(row, signature);
            return hits.stream().map(hit -> corpus.basis(row.sourceId(), hit)).toList();
        });
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
            boolean inheritedDay,
            QueryUsage.ParameterUse parameterUse) {}

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
        if (seconds >= MIN_HISTORY_BUDGET_SECONDS) {
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
     * 已捕获结果可被新调度进程交付，只重新核对权限和固定版本。
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
            runAccess.verifyBinding(row, captured.bindingId(), captured.ruleSignature(), settings.configuration());
            if (requests.deliver(row.id(), row.runVersion()) != 1) {
                // 原状态仍由台账决定，取消意图和租约竞争不会在这里冒充取消成功。
                throw QueryFailure.technical();
            }
            explanations.initial(row.id());
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
