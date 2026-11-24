package com.dataocean.module.knowledge.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dataocean.common.Ids;
import com.dataocean.common.PageQuery;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.governance.entity.vo.GovernanceFactVO;
import com.dataocean.module.governance.service.GovernanceKnowledgeService;
import com.dataocean.module.governance.service.GovernanceTarget;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ServiceActorPermission;
import com.dataocean.module.knowledge.entity.dto.KnowledgePlanDTO;
import com.dataocean.module.knowledge.entity.vo.KnowledgePlanVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgePlanVO.DocumentReference;
import com.dataocean.module.knowledge.entity.vo.KnowledgePlanVO.FactReference;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper.Document;
import com.dataocean.module.knowledge.mapper.KnowledgePlanMapper;
import com.dataocean.module.knowledge.service.KnowledgePlanningWire.Fact;
import com.dataocean.module.knowledge.service.KnowledgePlanningWire.Request;
import com.dataocean.module.knowledge.service.KnowledgePlanningWire.Response;
import com.dataocean.module.knowledge.service.KnowledgePlanningWire.Snapshot;
import com.dataocean.module.model.service.ModelRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import lombok.extern.slf4j.Slf4j;

/**
 * 从当前数据源的已确认治理事实提出知识文档建议。
 */
@Service
@Slf4j
public class KnowledgePlans {

    /**
     * 已登记的后台生成操作，独立于普通问数授权。
     */
    private static final String GENERATE = "knowledge.generate";

    /**
     * 单轮有界输入。超出时提示选择核心依据，避免静默漏掉同源事实。
     */
    private static final int MAX_INPUT_LENGTH = 100_000;

    /**
     * 固定规划和实际执行回执。
     */
    private final KnowledgePlanMapper plans;

    /**
     * 用现有的同源文档目录定位已有内容。
     */
    private final KnowledgeDocumentMapper documents;

    /**
     * 固定当前真实来源及连接修订。
     */
    private final GovernanceCatalog catalog;

    /**
     * 读取当前有效的治理确认。
     */
    private final GovernanceKnowledgeService governance;

    /**
     * 复用已部署的知识外发来源。
     */
    private final KnowledgeIndexProperties settings;

    /**
     * 使用本节点的固定模型和内部服务。
     */
    private final ModelRegistry models;

    /**
     * 从受认证的 Java 客户端向 Python 内部路由投递。
     */
    private final ObjectProvider<RestClient> worker;

    /**
     * 复核角色和实际发起人资格。
     */
    private final ServiceActorPermission actors;

    /**
     * 用户回看时的知识页面权限。
     */
    private final PermissionService permissions;

    /**
     * 用公共命令协议保障重复提交。
     */
    private final MutationService commands;

    /**
     * 记录发起规划的操作和归属。
     */
    private final AuditService audit;

    /**
     * 模型凭据以外的固定快照使用标准 JSON。
     */
    private final JsonCodec json;

    /**
     * 正文和规划沿用同一条单次派发队列。
     */
    private final KnowledgeDraftGenerations drafts;

    /**
     * 标准题建议复用知识任务领取、期限与失败状态。
     */
    private final KnowledgeTestSuggestions questions;

    /**
     * 内部客户选择带模型响应预算的现有签名连接。
     */
    public KnowledgePlans(
            KnowledgePlanMapper plans,
            KnowledgeDocumentMapper documents,
            GovernanceCatalog catalog,
            GovernanceKnowledgeService governance,
            KnowledgeIndexProperties settings,
            ModelRegistry models,
            @Qualifier("knowledgePlanPythonClient") ObjectProvider<RestClient> worker,
            ServiceActorPermission actors,
            PermissionService permissions,
            MutationService commands,
            AuditService audit,
            JsonCodec json,
            KnowledgeDraftGenerations drafts,
            KnowledgeTestSuggestions questions) {
        this.plans = plans;
        this.documents = documents;
        this.catalog = catalog;
        this.governance = governance;
        this.settings = settings;
        this.models = models;
        this.worker = worker;
        this.actors = actors;
        this.permissions = permissions;
        this.commands = commands;
        this.audit = audit;
        this.json = json;
        this.drafts = drafts;
        this.questions = questions;
    }

    /**
     * 受理时固定当前源、事实及模型修订，重复命令回原任务。
     */
    public MutationVO create(long sourceId, String command, KnowledgePlanDTO input) {
        permissions.require("knowledge.view");
        return commands.execute(GENERATE, command, "knowledge-plan:" + sourceId, input, null, false, false, () -> {
            var source = eligibleSource(sourceId);
            long revision = models.requireBinding(ModelRegistry.DOCUMENT_PLAN);
            models.verifyActive(revision);
            var snapshot = capture(source, input);
            var actor = UserContext.current();
            long id = IdWorker.getId();
            plans.insert(id, sourceId, actor.userId(), actor.authenticationVersion(), revision, json.write(snapshot));
            audit.record(AuditWriteDTO.builder()
                    .actionCode(GENERATE)
                    .targetType("knowledge_plan")
                    .targetId(Ids.text(id))
                    .targetName(source.name())
                    .securityVersion(commands.auditSecurityVersion(false))
                    .commandId(UUID.fromString(command).toString())
                    .build());
            return new MutationVO(command, "knowledge_plan", Ids.text(id), 1);
        });
    }

    /**
     * 个人规划回看核对当前权限、发起人和原数据源。
     */
    public KnowledgePlanVO detail(long sourceId, long id) {
        permissions.require("knowledge.view");
        var row = plans.get(id);
        if (row == null || !"PLAN".equals(row.operation()) || row.sourceId() != sourceId
                || row.requestedBy() != UserContext.getUser()) {
            // 同一任务不可通过其他数据源或后台账号访问。
            throw BizIllegalException.missing();
        }
        if (row.result() == null) {
            // 失败或执行过程只给出状态，未完成输入不进入界面。
            return new KnowledgePlanVO(Ids.text(id), row.state(), row.failureCode(),
                    List.of(), List.of(), List.of(), List.of());
        }
        var snapshot = json.read(row.snapshot(), Snapshot.class);
        var facts = snapshot.facts().stream()
                .map(input -> {
                    var fact = input.fact();
                    var target = fact.getTarget();
                    return new FactReference(
                            input.number(),
                            fact.getLabel(),
                            target.getObjectId(),
                            target.getKind(),
                            target.getTargetId(),
                            fact.getRevisionId());
                })
                .toList();
        return new KnowledgePlanVO(
                Ids.text(id),
                row.state(),
                null,
                json.read(row.result(), Response.class).documents(),
                facts,
                snapshot.existingDocuments(),
                plans.draftTasks(id).stream()
                        .map(task -> new KnowledgePlanVO.DraftTask(task.suggestionIndex(), Ids.text(task.id())))
                        .toList());
    }

    /**
     * 有界单消费者独立于短周期平台任务，同一规划只外发一次。
     */
    @Scheduled(fixedDelay = 1000, scheduler = "knowledgePlanScheduler")
    public void dispatch() {
        plans.expire();
        var client = worker.getIfAvailable();
        var allowed = settings.getModelSourceIds();
        if (client == null || allowed.isEmpty()) {
            // 当前部署未启用知识模型外发时不领取。
            return;
        }
        Long id = plans.next(allowed);
        if (id == null || plans.claim(id) != 1) {
            // 并行实例的活跃任务已由实际领取者负责。
            return;
        }
        var job = plans.get(id);
        if ("WRITE".equals(job.operation())) {
            // 正文和规划各自解析固定输入，领取与终态保护共用。
            drafts.dispatch(job, client);
            return;
        }
        if (KnowledgeTestSuggestions.OPERATION.equals(job.operation())) {
            // 标准题建议只向其独立节点发出已确认的正文输入。
            questions.dispatch(job, client);
            return;
        }
        try {
            var snapshot = json.read(job.snapshot(), Snapshot.class);
            var model = actors.asActor(
                    job.requestedBy(),
                    job.authenticationVersion(),
                    () -> {
                        verifySnapshot(eligibleSource(job.sourceId()), snapshot);
                        return models.invocation(job.modelRevisionId(), 55);
                    },
                    "knowledge.view",
                    GENERATE);
            var result = client.post()
                    .uri("/internal/v1/knowledge/document-plans")
                    .body(new Request(Ids.text(id), model, snapshot))
                    .retrieve()
                    .body(Response.class);
            actors.require(job.requestedBy(), job.authenticationVersion(), "knowledge.view", GENERATE);
            if (!valid(result, snapshot)) {
                // 模型不能补造确认身份或引用其他文档。
                plans.fail(id, "INVALID_PLAN");
                return;
            }
            plans.complete(id, json.write(result));
        } catch (ResourceAccessException unknown) {
            // 传输中断后可能已经产生提供方调用，保留未知并不重放。
            plans.fail(id, "MODEL_RESULT_UNKNOWN");
        } catch (RestClientException rejected) {
            // 模型端错误只以受控状态进入前台。
            plans.fail(id, "MODEL_FAILED");
        } catch (BizIllegalException changed) {
            // 撤权、确认修订变化或停用都应阻断本轮结果。
            plans.fail(id, "PLAN_INPUT_UNAVAILABLE");
        } catch (RuntimeException failure) {
            // 记录任务身份和异常分类，正文、提示词和凭据均不写日志。
            log.warn("知识规划执行失败，plan={}，异常类={}", id, failure.getClass().getSimpleName());
            plans.fail(id, "PLAN_FAILED");
        }
    }

    /**
     * 后台知识权限之外，持续使用既有确认知识的部署外发边界。
     */
    private Source eligibleSource(long sourceId) {
        var source = catalog.source(sourceId, false);
        if (!"ENABLED".equals(source.lifecycle()) || source.snapshotId() == null || source.scopePending()) {
            // 暂停或待核对目录不能成为正式规划输入。
            throw BizIllegalException.state("当前数据源尚无可用的已采集治理目录");
        }
        if (!settings.getModelSourceIds().contains(sourceId) || worker.getIfAvailable() == null) {
            // 禁止客户端自行提供外发地址或越过部署批准来源。
            throw BizIllegalException.state("当前数据源未启用知识模型调用");
        }
        return source;
    }

    /**
     * 逐页收集现行确认，并保留来源范围内已有文档的稳定标识。
     */
    private Snapshot capture(Source source, KnowledgePlanDTO input) {
        var facts = new ArrayList<Fact>();
        if (input.facts() == null) {
            for (long page = 1; ; page++) {
                // 缺省从源内全部可用确认中规划，不能只取第一分页。
                var options = governance.list(
                        source, null, null, new PageQuery().setPageNum(page).setPageSize(100L));
                options.getItems().stream()
                        .filter(option -> option.getFact() != null)
                        .forEach(option -> facts.add(new Fact(facts.size() + 1, option.getFact())));
                if (json.write(facts).length() > MAX_INPUT_LENGTH) {
                    throw new BizIllegalException(ErrorCode.CAPACITY_EXCEEDED, "治理资料超过单次规划容量，请选择本次范围");
                }
                if (page * 100 >= options.getTotal() || options.getItems().isEmpty()) {
                    break;
                }
            }
        } else {
            var selected = new HashSet<GovernanceTarget>();
            for (var choice : input.facts()) {
                // 指定范围时核对确切确认修订，并拒绝重复身份。
                var target = new GovernanceTarget(
                        source.id(),
                        Ids.parse(choice.getObjectId()),
                        choice.getKind(),
                        Ids.parse(choice.getTargetId()));
                var fact = governance.require(source, target);
                if (!selected.add(target) || !choice.getRevisionId().equals(fact.getRevisionId())) {
                    throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "所选治理确认需要重新核对");
                }
                facts.add(new Fact(facts.size() + 1, fact));
            }
        }
        if (facts.isEmpty()) {
            // 没有确认资料时不调用模型猜测业务。
            throw BizIllegalException.state("当前数据源没有可用于规划的已确认治理资料");
        }
        var existing = new ArrayList<DocumentReference>();
        for (long page = 1; ; page++) {
            // 只给模型名称与稳定编号，用于定位已有文档，不外发人工正文。
            var rows = documents
                    .list(new Page<Document>(page, 100, false), source.id(), null)
                    .getRecords();
            rows.forEach(row -> existing.add(new DocumentReference(Ids.text(row.id()), row.title())));
            if (rows.size() < 100) {
                break;
            }
        }
        var snapshot = new Snapshot(
                source.configurationId(), Objects.toString(input.goal(), "").strip(), source.name(), facts, existing);
        if (json.write(snapshot).length() > MAX_INPUT_LENGTH) {
            throw new BizIllegalException(ErrorCode.CAPACITY_EXCEEDED, "资料超过单次规划容量，请选择本次范围");
        }
        return snapshot;
    }

    /**
     * 发出模型请求前按同源身份复核每份固定治理确认和结构。
     */
    private void verifySnapshot(Source source, Snapshot snapshot) {
        if (!Objects.equals(source.configurationId(), snapshot.configuration())) {
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "来源配置已变化");
        }
        for (Fact item : snapshot.facts()) {
            // 普通采集变化不替换仍然有效的固定业务修订。
            GovernanceFactVO prior = item.fact();
            var target = prior.getTarget();
            var actual = governance.require(
                    source,
                    new GovernanceTarget(
                            source.id(),
                            Ids.parse(target.getObjectId()),
                            target.getKind(),
                            Ids.parse(target.getTargetId())));
            if (!prior.getRevisionId().equals(actual.getRevisionId())
                    || prior.getGeneration() != actual.getGeneration()
                    || !prior.getStructureRevisionId().equals(actual.getStructureRevisionId())) {
                throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "治理依据已变化");
            }
        }
    }

    /**
     * 接受结构化建议，引用只能来自本轮实际快照。
     */
    private boolean valid(Response result, Snapshot snapshot) {
        if (result == null || result.documents() == null) {
            return false;
        }
        var existing = new HashSet<String>();
        snapshot.existingDocuments().forEach(document -> existing.add(document.id()));
        return result.documents().stream()
                .allMatch(document -> document != null
                        && document.basis() != null
                        && !document.basis().isEmpty()
                        && document.basis().stream()
                                .allMatch(number -> number != null
                                        && number >= 1
                                        && number <= snapshot.facts().size())
                        && (document.existingDocumentId() == null || existing.contains(document.existingDocumentId())));
    }
}
