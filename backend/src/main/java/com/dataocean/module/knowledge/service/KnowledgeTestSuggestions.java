package com.dataocean.module.knowledge.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ServiceActorPermission;
import com.dataocean.module.knowledge.entity.dto.KnowledgeTestDraftDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeTestSuggestionAdoptionDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeTestSuggestionDTO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeDocumentVO.Content;
import com.dataocean.module.knowledge.entity.vo.KnowledgeTestSuggestionVO;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePlanMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePlanMapper.Row;
import com.dataocean.module.knowledge.mapper.KnowledgeTestMapper;
import com.dataocean.module.knowledge.service.KnowledgeTestSuggestionWire.Input;
import com.dataocean.module.knowledge.service.KnowledgeTestSuggestionWire.Report;
import com.dataocean.module.knowledge.service.KnowledgeTestSuggestionWire.Request;
import com.dataocean.module.knowledge.service.KnowledgeTestSuggestionWire.Response;
import com.dataocean.module.knowledge.service.KnowledgeTestSuggestionWire.Snapshot;
import com.dataocean.module.knowledge.service.KnowledgeTestSuggestionWire.Suggestion;
import com.dataocean.module.model.service.ModelRegistry;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import lombok.extern.slf4j.Slf4j;

/**
 * 用确切知识确认生成候选题，作者明确采纳后进入普通题单审核。
 */
@Service
@Slf4j
public class KnowledgeTestSuggestions {

    /**
     * 共用知识任务队列中标准题建议的类别。
     */
    static final String OPERATION = "QUESTION";

    /**
     * 生成并保存草稿沿用题单已有编辑能力。
     */
    private static final String EDIT = "test-cases.edit";

    /**
     * 标准题建议会读取并外发确认正文，因此同时要求文档查看权。
     */
    private static final String[] RIGHTS = {"test-cases.view", "knowledge.view", EDIT};

    /**
     * 用有界输入完整发送单篇核对过的知识内容。
     */
    private static final int MAX_INPUT_LENGTH = 100_000;

    /**
     * 可逐题核对的单次建议上限。
     */
    private static final int MAX_CANDIDATES = 8;

    /**
     * 正式题单现有覆盖枚举。
     */
    private static final Set<String> COVERAGES = Set.of("CAPABILITY", "BOUNDARY");

    /**
     * 正式题单现有判定方式。
     */
    private static final Set<String> MODES = Set.of("EXPLANATION", "NUMERIC");

    /**
     * 共用的任务状态和已采纳结果。
     */
    private final KnowledgePlanMapper tasks;

    /**
     * 查询原文档的确认修订和依赖。
     */
    private final KnowledgeDocumentMapper documents;

    /**
     * 现有题干和复用的题单草稿存储。
     */
    private final KnowledgeTestMapper questions;

    /**
     * 通过已有业务服务保存正式草稿和审计。
     */
    private final KnowledgeTests tests;

    /**
     * 核对实际来源和用于采纳的源锁。
     */
    private final GovernanceCatalog catalog;

    /**
     * 重新核对文档采用的治理依赖。
     */
    private final KnowledgeReferences references;

    /**
     * 沿用知识模型的部署准入范围。
     */
    private final KnowledgeIndexProperties settings;

    /**
     * 从独立节点获取并冻结真实模型配置。
     */
    private final ModelRegistry models;

    /**
     * 检查当前部署是否配置了签名的 Python 客户端。
     */
    private final ObjectProvider<RestClient> worker;

    /**
     * 派发和交付时复核真实发起人。
     */
    private final ServiceActorPermission actors;

    /**
     * 浏览、受理与采纳按当前角色核权。
     */
    private final PermissionService permissions;

    /**
     * 共用命令回查和事务。
     */
    private final MutationService commands;

    /**
     * 保存发起操作以及实际任务编号。
     */
    private final AuditService audit;

    /**
     * 任务内容及回包以类型化 JSON 保存。
     */
    private final JsonCodec json;

    /**
     * 与另外两个知识节点共用受认证内网连接和实际派发预算。
     */
    public KnowledgeTestSuggestions(
            KnowledgePlanMapper tasks, KnowledgeDocumentMapper documents, KnowledgeTestMapper questions,
            KnowledgeTests tests, GovernanceCatalog catalog, KnowledgeReferences references,
            KnowledgeIndexProperties settings, ModelRegistry models,
            @Qualifier("knowledgePlanPythonClient") ObjectProvider<RestClient> worker, ServiceActorPermission actors,
            PermissionService permissions, MutationService commands, AuditService audit, JsonCodec json) {
        this.tasks = tasks;
        this.documents = documents;
        this.questions = questions;
        this.tests = tests;
        this.catalog = catalog;
        this.references = references;
        this.settings = settings;
        this.models = models;
        this.worker = worker;
        this.actors = actors;
        this.permissions = permissions;
        this.commands = commands;
        this.audit = audit;
        this.json = json;
    }

    /**
     * 固定用户选择的确认修订。同一用户和修订的活动任务只受理一次外发。
     */
    public MutationVO create(long sourceId, String command, KnowledgeTestSuggestionDTO input) {
        return commands.execute(EDIT, command, "question-suggestion:" + sourceId, input, null, false, false, () -> {
            requireRights();
            var source = catalog.source(sourceId, true);
            requireModelSource(sourceId);
            long document = Ids.parse(input.documentId());
            long revision = Ids.parse(input.documentRevisionId());
            var content = confirmed(source, document, revision);
            var actor = UserContext.current();
            var last = tasks.latestQuestions(sourceId, actor.userId(), document, revision, true);
            if (last != null && Set.of("QUEUED", "RUNNING").contains(last.state())) {
                // 新命令命中并行操作时仍回到原任务。
                return accepted(command, last.id(), content.getTitle());
            }
            long model = models.requireBinding(ModelRegistry.QUESTION_SUGGESTION);
            models.verifyActive(model);
            var snapshot = new Snapshot(source.configurationId(),
                    new Input(content, questions.recentQuestions(sourceId, document)));
            var jsonSnapshot = json.write(snapshot);
            if (jsonSnapshot.length() > MAX_INPUT_LENGTH) {
                // 省略原正文会丢失使用边界，超过预算时完整拒绝。
                throw new BizIllegalException(ErrorCode.CAPACITY_EXCEEDED, "确认文档超过单次标准题建议容量");
            }
            long id = IdWorker.getId();
            tasks.insertQuestions(id, sourceId, actor.userId(), actor.authenticationVersion(), model, jsonSnapshot,
                    document, revision);
            return accepted(command, id, content.getTitle());
        });
    }

    /**
     * 从原文档上下文重新打开最近一次任务，刷新不会重新发起模型。
     */
    public KnowledgeTestSuggestionVO latest(long source, long document, long revision) {
        requireRights();
        var row = tasks.latestQuestions(source, UserContext.getUser(), document, revision, false);
        return row == null ? null : view(row);
    }

    /**
     * 指定编号的任务仍只在发起人及同源路径下可回看。
     */
    public KnowledgeTestSuggestionVO detail(long source, long id) {
        requireRights();
        return view(owned(tasks.get(id), source));
    }

    /**
     * 采纳保存普通草稿，复核确认版本和依赖，不写入实际完成或审核结果。
     */
    public MutationVO adopt(long sourceId, long id, String command, KnowledgeTestSuggestionAdoptionDTO input) {
        return commands.execute(EDIT, command, "question-adoption:" + id, input, null, false, false, () -> {
            requireRights();
            var source = catalog.source(sourceId, true);
            var row = owned(tasks.lock(id), sourceId);
            if (!"SUCCEEDED".equals(row.state())) {
                // 失败或尚在运行的任务不存在可保存的候选题。
                throw BizIllegalException.state("请等待标准题建议生成完成");
            }
            verify(source, row, snapshot(row));
            var report = json.read(row.result(), Report.class);
            int index = input.index();
            if (index >= report.candidates().size()) {
                // 索引只能定位已经校验并保存的真实建议。
                throw BizIllegalException.invalid("请选择可采纳的标准题建议");
            }
            var candidate = report.candidates().get(index);
            if (report.adoptions().containsKey(index)
                    || questions.sameQuestion(sourceId, row.questionDocumentId(), candidate.question()) != null) {
                // 同一源锁内拦截跨任务重复采纳和同题文本。
                throw BizIllegalException.state("题单已存在该题目，请刷新后查看");
            }
            var draft = new KnowledgeTestDraftDTO();
            draft.setDocumentId(Ids.text(row.questionDocumentId()));
            draft.setDocumentRevisionId(Ids.text(row.questionRevisionId()));
            draft.setQuestion(candidate.question());
            draft.setCoverage(candidate.coverage());
            draft.setMode(candidate.mode());
            if ("EXPLANATION".equals(candidate.mode())) {
                // 建议说明可供作者修订，独立的数据依据保留为空供人工填写。
                draft.setExpectedExplanation(candidate.validationHint());
            }
            var saved = tests.createDraft(sourceId, command, draft);
            var adopted = new HashMap<>(report.adoptions());
            adopted.put(index, saved.getTargetId());
            if (tasks.recordQuestionAdoption(id, json.write(new Report(report.candidates(), adopted))) != 1) {
                // 题目与采纳状态在同一事务提交或回滚。
                throw BizIllegalException.conflict();
            }
            return saved;
        });
    }

    /**
     * 单次派发后按实际回包和当前权限记录终态，连接未知不自行重发。
     */
    void dispatch(Row row, RestClient client) {
        long id = row.id();
        try {
            var snapshot = snapshot(row);
            var invocation = actors.asActor(row.requestedBy(), row.authenticationVersion(), () -> {
                // 只在实际外发前解开仍可用的固定模型修订。
                requireModelSource(row.sourceId());
                verify(catalog.source(row.sourceId(), false), row, snapshot);
                return models.invocation(row.modelRevisionId(), 55);
            }, RIGHTS);
            var response = client.post().uri("/internal/v1/knowledge/test-suggestions")
                    .body(new Request(Ids.text(id), invocation, snapshot.input()))
                    .retrieve().body(Response.class);
            if (!valid(response, snapshot.input().document().getSections().size())) {
                // 模型不能捏造文档章节或正式题单不认识的取值。
                tasks.fail(id, "INVALID_TEST_SUGGESTIONS");
                return;
            }
            actors.asActor(row.requestedBy(), row.authenticationVersion(), () -> {
                // 外发期间可能撤权、改确认或停用模型，交付之前再次检查。
                requireModelSource(row.sourceId());
                verify(catalog.source(row.sourceId(), false), row, snapshot);
                models.verifyActive(row.modelRevisionId());
                return null;
            }, RIGHTS);
            tasks.complete(id, json.write(new Report(unique(response, snapshot.input()), Map.of())));
        } catch (ResourceAccessException unknown) {
            // 已外发但响应未知，保留原任务待查调用。
            tasks.fail(id, "MODEL_RESULT_UNKNOWN");
        } catch (RestClientException failure) {
            // 对前端只保存受控分类。
            tasks.fail(id, "MODEL_FAILED");
        } catch (BizIllegalException changed) {
            // 实际源、文档确认、依赖或用户资格已经变化。
            tasks.fail(id, "TEST_INPUT_UNAVAILABLE");
        } catch (RuntimeException failure) {
            // 正文、题目、提示词和凭据均不进入日志。
            log.warn("标准题建议执行失败，task={}，异常类={}", id, failure.getClass().getSimpleName());
            tasks.fail(id, "TEST_SUGGESTIONS_FAILED");
        }
    }

    /**
     * 同源最新确认和所有已绑定治理引用必须可用。
     */
    private Content confirmed(Source source, long document, long revision) {
        var current = documents.get(source.id(), document);
        if (current == null) {
            // 不能通过其他数据源定位文档。
            throw BizIllegalException.missing();
        }
        if (!"ENABLED".equals(source.lifecycle()) || source.scopePending() || source.snapshotId() == null
                || !Objects.equals(current.confirmedRevisionId(), revision)) {
            // 新草稿可以编辑，但模型只使用当前确切确认修订。
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "请重新核对当前数据源和文档确认修订");
        }
        var saved = documents.revision(document, revision);
        if (saved == null) {
            // 历史正文无法读取时停止生成与采纳。
            throw BizIllegalException.missing();
        }
        references.verify(source, documents.references(revision));
        return json.read(saved.content(), Content.class);
    }

    /**
     * 状态续查和采纳保持原来源配置与确切确认。
     */
    private void verify(Source source, Row row, Snapshot snapshot) {
        if (!Objects.equals(source.configurationId(), snapshot.configuration())) {
            // 换源连接后要求重新发起建议。
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "来源配置已变化，请重新生成标准题建议");
        }
        confirmed(source, row.questionDocumentId(), row.questionRevisionId());
    }

    /**
     * 只对部署已准入来源开放真实模型调用。
     */
    private void requireModelSource(long source) {
        if (!settings.getModelSourceIds().contains(source) || worker.getIfAvailable() == null) {
            // 只读回看和已生成结果的本地采纳不依赖该调用地址。
            throw BizIllegalException.state("当前数据源未启用知识模型调用");
        }
    }

    /**
     * 正确回包最多八道，每道都能用原正文实际章节追溯。
     */
    static boolean valid(Response response, int sections) {
        return response != null && response.candidates() != null && response.candidates().size() <= MAX_CANDIDATES
                && response.candidates().stream().allMatch(candidate -> candidate != null
                        && hasText(candidate.question(), 300) && hasText(candidate.validationHint(), 800)
                        && candidate.coverage() != null && COVERAGES.contains(candidate.coverage())
                        && candidate.mode() != null && MODES.contains(candidate.mode())
                        && candidate.sections() != null && !candidate.sections().isEmpty()
                        && candidate.sections().stream().allMatch(index -> index != null && index >= 0 && index < sections));
    }

    /**
     * 与草稿题干及页面判定提示的长度约束一致。
     */
    private static boolean hasText(String text, int max) {
        return text != null && !text.isBlank() && text.length() <= max;
    }

    /**
     * 去掉模型重复和已知的同题建议，问法近似仍由人工核对。
     */
    private List<Suggestion> unique(Response response, Input input) {
        var seen = new HashSet<String>();
        input.existingQuestions().forEach(question -> seen.add(question.strip().toLowerCase(Locale.ROOT)));
        return response.candidates().stream()
                .filter(candidate -> seen.add(candidate.question().strip().toLowerCase(Locale.ROOT)))
                .map(candidate -> new Suggestion(candidate.question().strip(), candidate.coverage(), candidate.mode(),
                        candidate.validationHint().strip(), candidate.sections().stream().distinct().toList()))
                .toList();
    }

    /**
     * 回看历史任务时只交付建议和引用章节名称，保持以原编号核对草稿状态。
     */
    private KnowledgeTestSuggestionVO view(Row row) {
        var candidates = List.<KnowledgeTestSuggestionVO.Candidate>of();
        if (row.result() != null) {
            // 解开原任务章节，模型回包中的序号已在交付前校验。
            var sections = snapshot(row).input().document().getSections();
            var report = json.read(row.result(), Report.class);
            candidates = IntStream.range(0, report.candidates().size()).mapToObj(index -> {
                var item = report.candidates().get(index);
                return new KnowledgeTestSuggestionVO.Candidate(item.question(), item.coverage(), item.mode(),
                        item.validationHint(), item.sections().stream().map(i -> sections.get(i).getTitle()).toList(),
                        report.adoptions().get(index));
            }).toList();
        }
        return new KnowledgeTestSuggestionVO(Ids.text(row.id()), Ids.text(row.questionDocumentId()),
                Ids.text(row.questionRevisionId()), row.state(), row.failureCode(), candidates);
    }

    /**
     * 精确核验任务类别、来源和发起人。
     */
    private Row owned(Row row, long source) {
        if (row == null || !OPERATION.equals(row.operation()) || row.sourceId() != source
                || row.requestedBy() != UserContext.getUser()) {
            // 规划、正文与其他作者的建议不会通过本接口展示。
            throw BizIllegalException.missing();
        }
        return row;
    }

    /**
     * 读取无凭据的原任务快照。
     */
    private Snapshot snapshot(Row row) {
        return json.read(row.snapshot(), Snapshot.class);
    }

    /**
     * 复用已登记的题单权限和文档查看能力。
     */
    private void requireRights() {
        for (String right : RIGHTS) {
            // 题单权限与原文权限分别确认。
            permissions.require(right);
        }
    }

    /**
     * 实际受理与同事务的操作审计使用原任务编号。
     */
    private MutationVO accepted(String command, long id, String title) {
        audit.record(AuditWriteDTO.builder().actionCode(EDIT).targetType("knowledge_test_suggestion")
                .targetId(Ids.text(id)).targetName(title).securityVersion(commands.auditSecurityVersion(false))
                .commandId(command).build());
        return new MutationVO(command, "knowledge_test_suggestion", Ids.text(id), 1);
    }
}
