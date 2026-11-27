package com.dataocean.module.feedback.service.impl;

import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.ASSIGN;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.VIEW;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.assignable;
import static com.dataocean.module.feedback.service.impl.FeedbackIssueRules.requireIssue;
import static com.dataocean.module.model.service.ModelRegistry.FEEDBACK_SIMILARITY;

import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.access.entity.vo.AccessScopeVO;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.feedback.entity.vo.FeedbackSimilarityCandidateVO;
import com.dataocean.module.feedback.mapper.FeedbackMapper;
import com.dataocean.module.feedback.mapper.FeedbackMapper.Issue;
import com.dataocean.module.feedback.service.FeedbackSimilarityService;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.model.service.ModelInvocation;
import com.dataocean.module.model.service.ModelRegistry;
import com.dataocean.module.query.service.QueryService;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import lombok.extern.slf4j.Slf4j;

/**
 * 在同源、同等输出规则和当前证据权限下准备反馈相似候选。
 */
@Slf4j
@Service
public class FeedbackSimilarityServiceImpl implements FeedbackSimilarityService {

    /**
     * 读取事项与限定的同源候选。
     */
    private final FeedbackMapper issues;

    /**
     * 沿用当前身份、源访问和反馈证据检查。
     */
    private final PermissionService permissions;

    /**
     * 获取调用者在该源当前有效的输出规则。
     */
    private final AccessScopeService access;

    /**
     * 复用原问内容及历史签名的可披露判断。
     */
    private final QueryService queries;

    /**
     * 只在两次本地快照内与权限变更协调。
     */
    private final GuardMapper guard;

    /**
     * 单独固定反馈节点的实际模型修订。
     */
    private final ModelRegistry models;

    /**
     * 取得已签名的内部调用端点与有界响应时间。
     */
    private final ObjectProvider<RestClient> clients;

    /**
     * 在模型调用前后分别取得当前快照，锁不跨越外部等待。
     */
    private final TransactionTemplate transactions;

    /**
     * 部署批准向相似模型发送原问的数据源编号。
     */
    private final Set<Long> allowedSources;

    /**
     * 使用现有模型传输与事务设施，并独立约束反馈外发来源。
     */
    public FeedbackSimilarityServiceImpl(
            FeedbackMapper issues,
            PermissionService permissions,
            AccessScopeService access,
            QueryService queries,
            GuardMapper guard,
            ModelRegistry models,
            @Qualifier("modelTestPythonClient") ObjectProvider<RestClient> clients,
            TransactionTemplate transactions,
            @Value("${dataocean.feedback.similarity-source-ids:}") Set<Long> allowedSources) {
        this.issues = issues;
        this.permissions = permissions;
        this.access = access;
        this.queries = queries;
        this.guard = guard;
        this.models = models;
        this.clients = clients;
        this.transactions = transactions;
        this.allowedSources = allowedSources;
    }

    /**
     * 原问及候选绑定事项版本，减少授权或状态变化时的陈旧披露。
     */
    private record Candidate(String id, int version, String question, String supplement) {

        /**
         * 模型只得到实际可查看的问数条件。
         */
        String text() {
            return supplement == null ? question : question + "\n" + supplement;
        }
    }

    /**
     * 只读快照关联源、规则签名及实际候选。
     */
    private record Selection(String ruleSignature, Candidate origin, List<Candidate> candidates) {}

    /**
     * 内部调用携带当前配置及最小问数文本。
     */
    private record Input(String issueId, ModelInvocation model, String question, List<String> candidates) {}

    /**
     * Python 只返回对应于送出候选的零起始序号。
     */
    private record Ranking(List<Integer> order) {}

    /**
     * 每次操作最多发出一次向量请求，返回前重新读取实际权限和证据。
     */
    @Override
    public List<FeedbackSimilarityCandidateVO> find(String id) {
        long issue = Ids.parse(id);
        var first = Objects.requireNonNull(transactions.execute(status -> select(issue)));
        if (first.candidates().isEmpty()) {
            // 空范围无需领取模型凭据或发起额外调用。
            return List.of();
        }
        var client = clients.getIfAvailable();
        Long revision = models.activeBinding(FEEDBACK_SIMILARITY);
        if (client == null || revision == null) {
            // 现有手动选择仍可使用，向处理人说明相似推荐暂不可用。
            throw BizIllegalException.state("相似候选模型暂不可用");
        }
        var model = models.invocation(revision, 15);
        var ranking = rank(client, first, model);
        if (!first.equals(transactions.execute(status -> select(issue)))) {
            // 条件或候选期间变化时，要求重新请求可核查的建议。
            throw BizIllegalException.conflict();
        }
        return ranking.stream()
                .map(first.candidates()::get)
                .map(candidate -> new FeedbackSimilarityCandidateVO(candidate.id(), candidate.question()))
                .toList();
    }

    /**
     * 在当前权限下固定候选来源、可访问的原问及证据签名。
     */
    private Selection select(long id) {
        guard.shared();
        permissions.require(VIEW);
        permissions.require(ASSIGN);
        permissions.require("feedback.evidence");
        permissions.require("query.view");
        var origin = requireIssue(issues.issue(id));
        var scope =
                access.findSource(UserContext.getUser(), origin.sourceId()).orElseThrow(BizIllegalException::denied);
        var question = candidate(origin, scope);
        if (question == null) {
            // 缺少原问的完整证据或资格时不能调用模型。
            throw BizIllegalException.denied();
        }
        if (!allowedSources.contains(origin.sourceId())) {
            // 来源模型外发需独立准入。
            throw BizIllegalException.state("该数据源未启用相似候选");
        }
        var eligible = issues.similarityCandidates(origin.sourceId(), id).stream()
                .map(row -> candidate(row, scope))
                .filter(Objects::nonNull)
                .toList();
        return new Selection(queries.feedbackRuleSignature(scope), question, eligible);
    }

    /**
     * 仅取可以独立理解且完整可披露的原问和补充。
     */
    private Candidate candidate(Issue issue, AccessScopeVO scope) {
        if (!Ids.text(issue.sourceId()).equals(scope.getSource().getId())
                || !assignable(issue)
                || issue.mergedInto() != null
                || !queries.canReplayFeedback(issue.requestId(), scope)) {
            // 不带入已结案、已归并或依赖不可披露历史的原问。
            return null;
        }
        var question = queries.feedbackQuestion(issue.requestId(), scope);
        if (question == null || question.getQuestion() == null) {
            // 实际证据缺失时不使用事项摘要冒充提问内容。
            return null;
        }
        return new Candidate(Ids.text(issue.id()), issue.version(), question.getQuestion(), question.getSupplement());
    }

    /**
     * 确认收到有限且唯一的候选索引，网络故障时保留手动合并入口。
     */
    private List<Integer> rank(RestClient client, Selection selection, ModelInvocation model) {
        var input = new Input(
                selection.origin().id(),
                model,
                selection.origin().text(),
                selection.candidates().stream().map(Candidate::text).toList());
        Ranking ranking;
        try {
            // 只有当前快照许可的原问参与本次排序。
            ranking = client.post()
                    .uri("/internal/v1/feedback/similarity")
                    .body(input)
                    .retrieve()
                    .body(Ranking.class);
        } catch (RestClientException unavailable) {
            // 不输出请求、凭据或提供方正文。
            log.info(
                    "相似候选调用未完成，issue={}，分类={}",
                    input.issueId(),
                    unavailable.getClass().getSimpleName());
            throw BizIllegalException.state("相似候选暂时无法读取，请重试或手动搜索");
        }
        var order = ranking == null ? null : ranking.order();
        if (order == null
                || order.size() > 5
                || order.stream()
                        .anyMatch(index -> index == null
                                || index < 0
                                || index >= input.candidates().size())
                || new HashSet<>(order).size() != order.size()) {
            // 内部结果只可回指本次实际发送的候选。
            throw BizIllegalException.state("相似候选结果无效，请重试或手动搜索");
        }
        return order;
    }
}
