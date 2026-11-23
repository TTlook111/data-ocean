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
import com.dataocean.module.query.mapper.QueryRequestMapper;
import com.dataocean.module.query.mapper.QueryRequestMapper.Request;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

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
    /**
     * 保存数量符号。日历中连接年月日的连字符不作为负号。
     */
    private static final Pattern NUMBER = Pattern.compile("(?:(?<!\\d)[+−-])?\\d+(?:\\.\\d+)?");

    /** 和结果交付在同一事务接收首次解释。 */
    void initial(String request) {
        revisions.add(request, 1, request);
    }

    /** 当前已获结果权限才会回读解释。 */
    QueryExplanationVO view(String request) {
        var row = revisions.latest(request);
        return row == null ? null : new QueryExplanationVO(row.revision(), row.status(), row.text());
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
            requireResult(row);
            var replay = revisions.byCommand(request, key);
            if (replay != null) {
                return new QueryExplanationVO(replay.revision(), replay.status(), replay.text());
            }
            var previous = revisions.latest(request);
            if (previous != null && !Set.of("FAILED", "STOPPED").contains(previous.status())) {
                throw BizIllegalException.state("当前说明已有结果或正在处理");
            }
            int next = previous == null ? 1 : previous.revision() + 1;
            if (next > 4 || row.remainingMillis() < 2000) {
                throw BizIllegalException.state("本次说明的可用处理预算已用完");
            }
            revisions.add(request, next, key);
            return new QueryExplanationVO(next, "QUEUED", null);
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
                models.requireExplanationScope(query.sourceId());
                long model = models.revision("result-explanation");
                int budget = Math.min(22000, query.remainingMillis());
                if (data.getRows().size() > 20
                        || budget < 2000
                        || queries.reserveExplanation(query.id(), budget) != 1) {
                    throw QueryFailure.resource();
                }
                revisions.claim(query.id(), job.revision(), model);
                return new Work(query, job.revision(), model, budget / 1000, data);
            } catch (BizIllegalException | QueryFailure unavailable) {
                // 解释缺少配置、权限或预算只影响当前说明修订。
                revisions.claim(job.requestId(), job.revision(), null);
                revisions.finish(job.requestId(), job.revision(), "FAILED", null, "NOT_AVAILABLE");
                return null;
            }
        });
        if (task == null) {
            return;
        }
        try {
            var row = task.query();
            requireResult(queries.get(row.id()));
            String text = models.explain(
                            row.id(), task.revision(), task.model(), task.seconds(), row.question(), task.data())
                    .strip();
            verifyFacts(row, task.data(), text);
            tx.executeWithoutResult(status -> {
                guard.shared();
                requireResult(queries.get(row.id()));
                revisions.finish(row.id(), task.revision(), "COMPLETED", text, null);
            });
        } catch (RuntimeException unavailable) {
            // 不写入模型正文、访问凭据或未知数据。
            revisions.finish(task.query().id(), task.revision(), "FAILED", null, "EXPLANATION_FAILED");
        }
    }

    /** 结果授权、留存、同版知识和原规则签名全部匹配时才能解释。 */
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
                    return json.read(row.result(), QueryDataVO.class);
                },
                "query.view",
                "query.ask");
    }

    /** 解释不能添加快照外数字、隐含采购信息或更换核心结果。 */
    private void verifyFacts(Request row, QueryDataVO data, String text) {
        if (text.isBlank() || text.length() > 1000) {
            throw QueryFailure.technical();
        }
        var allowed = new HashSet<BigDecimal>();
        NUMBER.matcher(row.question() + " " + data.getScope())
                .results()
                .forEach(match -> allowed.add(number(match.group())));
        data.getRows().stream()
                .flatMap(java.util.Collection::stream)
                .filter(Objects::nonNull)
                .forEach(value -> NUMBER.matcher(value).results().forEach(match -> allowed.add(number(match.group()))));
        var described = NUMBER.matcher(text)
                .results()
                .map(match -> number(match.group()))
                .toList();
        if (!allowed.containsAll(described)) {
            throw QueryFailure.knowledge();
        }
        if (data.getRows().size() == 1 && data.getColumns().size() == 1) {
            var value = data.getRows().get(0).get(0);
            if (value != null && NUMBER.matcher(value).matches() && !described.contains(number(value))) {
                throw QueryFailure.knowledge();
            }
        }
        output.checkDisclosure(
                access.requireSource(row.userId(), row.sourceId()).getRules(), text);
    }

    /**
     * 将同值小数规范化比较，避免格式尾零导致拒绝，也保留负值方向。
     */
    private BigDecimal number(String token) {
        return new BigDecimal(token.replace('−', '-')).stripTrailingZeros();
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
