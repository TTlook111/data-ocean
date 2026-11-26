package com.dataocean.module.query.service;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.service.ServiceActorPermission;
import com.dataocean.module.knowledge.service.KnowledgeVersionService;
import com.dataocean.module.query.entity.vo.QueryDataVO;
import com.dataocean.module.query.entity.vo.QueryExplanationVO;
import com.dataocean.module.query.mapper.QueryExplanationMapper;
import com.dataocean.module.query.mapper.QueryExplanationMapper.Explanation;
import com.dataocean.module.query.mapper.QueryRequestMapper;
import com.dataocean.module.query.mapper.QueryRequestMapper.Request;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;

/**
 * 独立解释队列只消费已经交付并重新核权的限量快照。
 */
@Service
@RequiredArgsConstructor
public class QueryExplanations {

    /** 查询快照始终以原请求为权威。 */
    private final QueryRequestMapper queries;
    /** 每次手动操作形成自己的不可覆盖修订。 */
    private final QueryExplanationMapper revisions;
    /** 出入阶段都按当前身份与源访问检查。 */
    private final ServiceActorPermission actors;
    /** 解释仍遵循同一发布许可与实际输出限制。 */
    private final KnowledgeVersionService knowledge;
    /** 当前输出限制来源。 */
    private final AccessScopeService access;
    /** 短小的权限、预算及接收事务。 */
    private final TransactionTemplate tx;
    /** 与现行授权修改串行。 */
    private final GuardMapper guard;
    /** 仅对已获准有限结果外发。 */
    private final QueryModels models;
    /** 持久化结果与业务模型转换。 */
    private final JsonCodec json;
    /** 强制保持原查询输出限制。 */
    private final QueryOutput output = new QueryOutput();
    /** 一次说明向模型提供的结果行数上限。 */
    private static final int MAX_RESULT_ROWS = 20;

    /** 一段说明最多展示三条快照事实。 */
    private static final int MAX_SELECTED_FACTS = 3;

    /**
     * 结果说明正文的长度上限，单条候选和最终组合使用同一限制。
     */
    private static final int MAX_TEXT_LENGTH = 1000;

    /** 至少预留两秒才可请求说明。 */
    private static final int MIN_BUDGET_MILLIS = 2000;

    /** 初次说明及最多三次手动请求所用的修订上限。 */
    private static final int MAX_REVISION = 4;

    /** 说明外发未准入时的业务反馈。 */
    private static final String SOURCE_BLOCK = "当前数据源未启用结果说明";

    /** 已结束且可继续申请生成的持久化状态。 */
    private static final Set<String> REVISABLE = Set.of("FAILED", "STOPPED", "UNAVAILABLE");

    /**
     * 用交付后结算的预算和实际快照决定首次说明是否入队。
     */
    void initial(Request delivered) {
        String state = "UNAVAILABLE";
        try {
            var data = json.read(delivered.result(), QueryDataVO.class);
            if (data != null && data.getRows() != null && generationBlock(delivered, data, 1) == null) {
                // 只有符合领取条件的首次说明才进入等待队列。
                state = "QUEUED";
            }
        } catch (JacksonException invalid) {
            // 说明无法使用旧格式快照时，保留已捕获结果的交付。
        }
        revisions.add(delivered.id(), 1, delivered.id(), state);
    }

    /** 订阅只需要说明进度，不必解码完整的查询结果。 */
    QueryExplanationVO view(String request, long source) {
        var row = revisions.latest(request);
        return row == null ? null : viewRevision(row, source);
    }

    /** 工作台详情复用查数快照及后端入队资格。 */
    QueryExplanationVO snapshot(Request query, QueryDataVO data) {
        var row = revisions.latest(query.id());
        return row == null ? null : snapshotRevision(row, query, data);
    }

    /** 在业务详情区分当前限制、已生成和待生成的说明。 */
    private QueryExplanationVO snapshotRevision(Explanation row, Request query, QueryDataVO data) {
        var view = viewRevision(row, query.sourceId());
        if ("COMPLETED".equals(view.getStatus())) {
            // 原已交付说明仍由读结果权限保护。
            return view;
        }
        var block = "QUEUED".equals(row.status())
                ? generationBlock(query, data, row.revision())
                : resultBlock(query.sourceId(), data);
        if (block != null) {
            // 旧排队修订也按当前资格展示，用户不用等待后台拒绝才看到金额。
            view.setStatus("UNAVAILABLE");
            view.setBlockedReason(block);
        } else if (REVISABLE.contains(row.status())) {
            // 已终止的历史修订只在下一次仍可调度时提供入口。
            view.setBlockedReason(retryBlock(query, data, row));
        }
        return view;
    }

    /** 只有已交付说明保留正文，未调用过模型的修订按真实状态展示。 */
    private QueryExplanationVO viewRevision(Explanation row, long source) {
        String state = row.status();
        if ("COMPLETED".equals(state)) {
            return new QueryExplanationVO(row.revision(), state, row.text(), null);
        }
        if (!models.explanationSourceAdmitted(source)) {
            // 即使历史说明曾失败，当前准入仍是新生成的先决条件。
            return new QueryExplanationVO(row.revision(), "UNAVAILABLE", null, SOURCE_BLOCK);
        }
        if ("UNAVAILABLE".equals(state)
                || "FAILED".equals(state) && row.modelRevisionId() == null && "NOT_AVAILABLE".equals(row.failureCode())) {
            // 新旧记录只要在调用前停止，就没有一次生成结果。
            state = "NOT_GENERATED";
        }
        return new QueryExplanationVO(row.revision(), state, null, null);
    }

    /**
     * 说明只使用获准且能完整交付的结果事实。
     */
    private String resultBlock(long source, QueryDataVO data) {
        if (!models.explanationSourceAdmitted(source)) {
            // 来源准入限制适用于任何结果形态。
            return SOURCE_BLOCK;
        }
        if (data.getRows().size() > MAX_RESULT_ROWS) {
            // 超出行数上限的结果不构造模型候选。
            return "结果超过 20 行，无法生成说明";
        }
        return deliverableFacts(data).isEmpty() ? "结果中的单条事实超出说明长度限制" : null;
    }

    /**
     * 前置调度、手动请求与工作台复用同一套说明限制。
     */
    private String generationBlock(Request query, QueryDataVO data, int revision) {
        var resultBlock = resultBlock(query.sourceId(), data);
        if (resultBlock != null) {
            // 已知无法说明的结果不占用本次请求预算。
            return resultBlock;
        }
        if (revision > MAX_REVISION) {
            // 生成请求超过修订上限时不再创建任务。
            return "本次说明的重试次数已用完";
        }
        return query.remainingMillis() < MIN_BUDGET_MILLIS ? "本次说明的可用处理预算已用完" : null;
    }

    /**
     * 当前请求能否新建说明，取决于最新修订而非某条历史命令。
     */
    private String retryBlock(Request query, QueryDataVO data, Explanation latest) {
        if (latest != null && !REVISABLE.contains(latest.status())) {
            // 已有正文或在途任务时保留当前修订。
            return "当前说明已有结果或正在处理";
        }
        return generationBlock(query, data, latest == null ? 1 : latest.revision() + 1);
    }

    /** 重试按实际请求、最新修订和剩余预算受控。 */
    public QueryExplanationVO retry(String id, String command) {
        String request = uuid(id);
        String key = uuid(command);
        return tx.execute(status -> {
            guard.shared();
            var row = queries.lock(request);
            if (row == null || row.userId() != UserContext.getUser()) {
                throw BizIllegalException.missing();
            }
            var data = requireResult(row);
            var replay = revisions.byCommand(request, key);
            var latest = revisions.latest(request);
            var block = retryBlock(row, data, latest);
            if (replay != null) {
                // 幂等回执保留原修订，新的操作资格来自当前请求。
                var receipt = viewRevision(replay, row.sourceId());
                receipt.setBlockedReason(block);
                return receipt;
            }
            if (block != null) {
                // 当前资格不满足时，在创建修订前给出与页面一致的原因。
                throw BizIllegalException.state(block);
            }
            int next = latest == null ? 1 : latest.revision() + 1;
            revisions.add(request, next, key, "QUEUED");
            return new QueryExplanationVO(next, "QUEUED", null, null);
        });
    }

    /** 停止尚未完整交付的解释。 */
    void stop(String request) {
        revisions.stop(request);
    }

    /** 未收到回执的过期解释允许明确重试，查询结果仍可读。 */
    void expire() {
        revisions.expire();
    }

    /** 解释节点没有 JDBC 依赖，进程中断时后续重试不会重发原查询。 */
    @Scheduled(fixedDelay = 650, scheduler = "queryScheduler")
    void work() {
        var task = tx.execute(status -> {
            guard.shared();
            var candidate = revisions.next();
            if (candidate == null) {
                return null;
            }
            var query = queries.lock(candidate.requestId());
            var job = revisions.lock(candidate.requestId(), candidate.revision());
            if (job == null || !"QUEUED".equals(job.status())) {
                // 其他调度者或取消已经处理，不能继续预留预算。
                return null;
            }
            try {
                var data = requireResult(query);
                if (generationBlock(query, data, job.revision()) != null) {
                    // 领取时补验旧队列以及提交后变化的资格。
                    finishWithoutModel(job, "UNAVAILABLE");
                    return null;
                }
                models.requireExplanationScope(query.sourceId());
                long model = models.revision("result-explanation");
                int budget = Math.min(22000, query.remainingMillis());
                if (queries.reserveExplanation(query.id(), budget) != 1) {
                    throw QueryFailure.resource();
                }
                revisions.claim(query.id(), job.revision(), model);
                return new Work(query, job.revision(), model, budget / 1000, data);
            } catch (BizIllegalException | QueryFailure unavailable) {
                // 解释缺少配置、权限或预算只影响当前说明修订。
                finishWithoutModel(job, "FAILED");
                return null;
            }
        });
        if (task == null) {
            return;
        }
        try {
            var row = task.query();
            requireResult(queries.get(row.id()));
            var facts = deliverableFacts(task.data());
            var selected = models.explain(
                    row.id(),
                    task.revision(),
                    task.model(),
                    task.seconds(),
                    task.data().getScope(),
                    facts);
            String text = renderSelectedFacts(facts, selected);
            tx.executeWithoutResult(status -> {
                // 正文只引用已核验投影的快照，交付前再次核对同版规则和权限。
                guard.shared();
                requireResult(queries.get(row.id()));
                revisions.finish(row.id(), task.revision(), "COMPLETED", text, null);
            });
        } catch (RuntimeException unavailable) {
            // 不写入模型正文、访问凭据或未知数据。
            revisions.finish(task.query().id(), task.revision(), "FAILED", null, "EXPLANATION_FAILED");
        }
    }

    /** 未派发模型的修订按已有领取和结算协议结束。 */
    private void finishWithoutModel(Explanation job, String status) {
        revisions.claim(job.requestId(), job.revision(), null);
        revisions.finish(job.requestId(), job.revision(), status, null, "NOT_AVAILABLE");
    }

    /**
     * 在当前权限和原规则签名仍有效时复用查数阶段已核验的投影。
     */
    private QueryDataVO requireResult(Request row) {
        if (row == null
                || !"RESULT_DELIVERED".equals(row.state())
                || row.result() == null
                || row.executedAt() == null
                || !row.executedAt().isAfter(LocalDateTime.now(ZoneOffset.UTC).minusDays(1))) {
            throw BizIllegalException.state("原查询结果已不可用");
        }
        return actors.asActor(
                row.userId(),
                row.authenticationVersion(),
                () -> {
                    var rules =
                            access.requireSource(row.userId(), row.sourceId()).getRules();
                    if (!output.signature(rules).equals(row.ruleSignature())) {
                        throw BizIllegalException.denied();
                    }
                    knowledge.requireForQuery(row.sourceId(), row.bindingId());
                    var data = json.read(row.result(), QueryDataVO.class);
                    output.checkResult(rules, data, json);
                    return data;
                },
                "query.view",
                "query.ask");
    }

    /**
     * 向模型提供自身能完整放入正文的候选，原行号继续指向查数快照。
     */
    private List<String> deliverableFacts(QueryDataVO data) {
        return factsFromSnapshot(data).stream()
                .filter(fact -> fact.length() <= MAX_TEXT_LENGTH)
                .toList();
    }

    /**
     * 用获准快照中的列标签、原值和单位组成事实，范围另取可披露的执行摘要。
     */
    private List<String> factsFromSnapshot(QueryDataVO data) {
        var rows = data.getRows();
        if (rows.isEmpty()) {
            // 空结果也作为本次执行的事实。
            return List.of("本次查询没有匹配记录。");
        }
        var columns = data.getColumns();
        if (rows.size() == 1 && columns.size() == 1) {
            // 单值说明直接交付实际指标，单位来自原快照。
            return List.of("本次查询的" + columns.get(0).getLabel() + "为 "
                    + displayValue(rows.get(0).get(0), data.getUnit()) + "。");
        }
        var facts = new ArrayList<String>();
        for (int index = 0; index < rows.size(); index++) {
            // 多行只陈述当前结果中选中行的真实列和值。
            var values = rows.get(index);
            var cells = new ArrayList<String>();
            for (int column = 0; column < columns.size(); column++) {
                // 日期列与实际汇总值分别按各自含义展示。
                var descriptor = columns.get(column);
                var unit = columns.size() == 1 || "value".equals(descriptor.getName()) ? data.getUnit() : null;
                cells.add(descriptor.getLabel() + "为 " + displayValue(values.get(column), unit));
            }
            facts.add("第 " + (index + 1) + " 行，" + String.join("，", cells) + "。");
        }
        return facts;
    }

    /**
     * 有值时保留快照的数值精度与单位，空值不附带单位。
     */
    private String displayValue(String value, String unit) {
        if (value == null) {
            return "暂无数据";
        }
        return unit == null || unit.isBlank() ? value : value + " " + unit;
    }

    /**
     * 校验引用并按模型顺序交付能完整放入正文的原句。
     */
    private String renderSelectedFacts(List<String> facts, List<Integer> selected) {
        if (selected == null || selected.isEmpty() || selected.size() > MAX_SELECTED_FACTS
                || new HashSet<>(selected).size() != selected.size()
                || selected.stream().anyMatch(id -> id == null || id < 1 || id > facts.size())) {
            // 越界、重复或缺少有效引用时不交付说明。
            throw QueryFailure.knowledge();
        }
        var text = new StringBuilder();
        for (int id : selected) {
            // 逐条保留完整事实，剩余空间不足时继续检查后续引用。
            var fact = facts.get(id - 1);
            if (fact.length() <= MAX_TEXT_LENGTH - text.length()) {
                // 原样写入快照事实，保留数值精度和单位。
                text.append(fact);
            }
        }
        return text.toString();
    }

    /** 解析命令编号时不回显无效内容。 */
    private String uuid(String value) {
        try {
            if (value != null && value.length() == 36) {
                return UUID.fromString(value).toString();
            }
        } catch (IllegalArgumentException invalid) {
            // 内部键规范要求完整 UUID。
        }
        throw BizIllegalException.invalid("请求编号格式不正确");
    }

    /** 本次领取到的独立修订与额度。 */
    private record Work(Request query, int revision, long model, int seconds, QueryDataVO data) {}
}
