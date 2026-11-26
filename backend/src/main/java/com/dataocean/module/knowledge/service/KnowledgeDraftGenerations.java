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
import com.dataocean.module.governance.entity.vo.GovernanceFactVO;
import com.dataocean.module.governance.service.GovernanceKnowledgeService;
import com.dataocean.module.governance.service.GovernanceTarget;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ServiceActorPermission;
import com.dataocean.module.knowledge.entity.dto.KnowledgeDocumentDraftDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeDocumentDraftDTO.Fact;
import com.dataocean.module.knowledge.entity.dto.KnowledgeDocumentDraftDTO.Section;
import com.dataocean.module.knowledge.entity.dto.KnowledgeDocumentUpdateDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeDraftAdoptionDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeDraftGenerationDTO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeDocumentVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeDraftGenerationVO;
import com.dataocean.module.knowledge.entity.vo.KnowledgePlanVO;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper.Reference;
import com.dataocean.module.knowledge.mapper.KnowledgePlanMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePlanMapper.Row;
import com.dataocean.module.knowledge.service.KnowledgeDraftGenerationWire.Basis;
import com.dataocean.module.knowledge.service.KnowledgeDraftGenerationWire.Brief;
import com.dataocean.module.knowledge.service.KnowledgeDraftGenerationWire.Change;
import com.dataocean.module.knowledge.service.KnowledgeDraftGenerationWire.Original;
import com.dataocean.module.knowledge.service.KnowledgeDraftGenerationWire.Request;
import com.dataocean.module.knowledge.service.KnowledgeDraftGenerationWire.Response;
import com.dataocean.module.knowledge.service.KnowledgeDraftGenerationWire.Snapshot;
import com.dataocean.module.model.service.ModelRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import lombok.extern.slf4j.Slf4j;

/**
 * 生成单篇知识正文，回读逐节差异并显式采纳到现有文档修订流程。
 */
@Service
@Slf4j
public class KnowledgeDraftGenerations {

    /**
     * 正文受理和回看沿用知识生成权限。
     */
    private static final String GENERATE = "knowledge.generate";

    /**
     * 单篇输入总量上限，覆盖正文和已确认治理资料。
     */
    private static final int MAX_INPUT_LENGTH = 100_000;

    /**
     * 文档已有的单份修订章节上限。
     */
    private static final int MAX_SECTIONS = 12;

    /**
     * 正文和规划共用任务队列与命令状态。
     */
    private final KnowledgePlanMapper tasks;

    /**
     * 草稿、历史和并发修订均复用正式文档服务。
     */
    private final KnowledgeDocuments documents;

    /**
     * 展开和固定当前章节的治理依赖。
     */
    private final KnowledgeReferences references;

    /**
     * 读取当前数据源和已确认治理事实。
     */
    private final GovernanceCatalog catalog;

    /**
     * 以权威源内身份复核冻结的治理资料。
     */
    private final GovernanceKnowledgeService governance;

    /**
     * 使用已有的知识模型来源准入。
     */
    private final KnowledgeIndexProperties settings;

    /**
     * 独立正文节点的模型绑定及密钥接口。
     */
    private final ModelRegistry models;

    /**
     * 判断知识内部服务是否在当前部署启用。
     */
    private final ObjectProvider<RestClient> worker;

    /**
     * 任务发起者的当前权限及可信业务上下文。
     */
    private final ServiceActorPermission actors;

    /**
     * 在线操作的真实权限检查。
     */
    private final PermissionService permissions;

    /**
     * 跨端命令、防重与文档写入的事务入口。
     */
    private final MutationService commands;

    /**
     * 保存正文生成任务的操作记录。
     */
    private final AuditService audit;

    /**
     * 已固定的正文、事实和结果均保存为强类型快照。
     */
    private final JsonCodec json;

    /**
     * 使用规划现有的受认证内部连接，避免为同类生成建立新客户端。
     */
    public KnowledgeDraftGenerations(
            KnowledgePlanMapper tasks,
            KnowledgeDocuments documents,
            KnowledgeReferences references,
            GovernanceCatalog catalog,
            GovernanceKnowledgeService governance,
            KnowledgeIndexProperties settings,
            ModelRegistry models,
            @Qualifier("knowledgePlanPythonClient") ObjectProvider<RestClient> worker,
            ServiceActorPermission actors,
            PermissionService permissions,
            MutationService commands,
            AuditService audit,
            JsonCodec json) {
        this.tasks = tasks;
        this.documents = documents;
        this.references = references;
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
    }

    /**
     * 受理用户核对后的建议，冻结确切依据及当前正文模型修订。
     */
    public MutationVO create(long sourceId, long planId, String command, KnowledgeDraftGenerationDTO input) {
        return commands.execute(GENERATE, command, "knowledge-draft:" + planId, input, null, false, false, () -> {
            permissions.require("knowledge.view");
            var plan = owned(tasks.lock(planId), sourceId, "PLAN");
            if (!"SUCCEEDED".equals(plan.state())) {
                // 只有已经实际形成并核对的建议能进入正文生成。
                throw BizIllegalException.state("请先完成文档规划");
            }
            var suggestions = json.read(plan.result(), KnowledgePlanningWire.Response.class).documents();
            int index = input.suggestionIndex();
            if (index >= suggestions.size()) {
                // 建议序号在已保存结果中解析。
                throw BizIllegalException.invalid("请重新选择规划建议");
            }
            var source = eligibleSource(sourceId);
            var snapshot = capture(source, plan, suggestions.get(index), input);
            var last = tasks.latestDraft(planId, index);
            if (last != null && (last.appliedDocumentId() != null
                    || (!"FAILED".equals(last.state()) && Objects.equals(snapshot, snapshot(last))))) {
                // 已采纳或同输入的在途与成功任务沿用原编号，避免重复外发。
                return accepted(command, last.id(), source.name());
            }
            long revision = models.requireBinding(ModelRegistry.DOCUMENT_WRITE);
            models.verifyActive(revision);
            var actor = UserContext.current();
            long id = IdWorker.getId();
            tasks.insertDraft(id, sourceId, actor.userId(), actor.authenticationVersion(), revision,
                    json.write(snapshot), planId, index);
            return accepted(command, id, source.name());
        });
    }

    /**
     * 只向同源任务的发起人展示可核对的逐节前后内容。
     */
    public KnowledgeDraftGenerationVO detail(long source, long planId, long id) {
        permissions.require("knowledge.view");
        var row = draft(tasks.get(id), source, planId);
        var snapshot = snapshot(row);
        if (snapshot.original() != null) {
            // 更新差异包含原人工正文，回看时继续核对编辑资格。
            permissions.require("knowledge.edit");
        }
        var changes = row.result() == null ? List.<KnowledgeDraftGenerationVO.Change>of()
                : json.read(row.result(), Response.class).changes().stream()
                        .map(change -> view(snapshot, change)).toList();
        return new KnowledgeDraftGenerationVO(Ids.text(id), row.suggestionIndex(), row.state(), row.failureCode(),
                snapshot.original() == null ? null : snapshot.original().id(),
                Ids.text(row.appliedDocumentId()), changes);
    }

    /**
     * 显式采纳才落实际草稿。同一建议的重复提交不会额外创建文档或修订。
     */
    public MutationVO adopt(long sourceId, long planId, long id, String command, KnowledgeDraftAdoptionDTO input) {
        return commands.execute("knowledge.edit", command, "knowledge-draft-adoption:" + id, input, null,
                false, false, () -> {
                    permissions.require(GENERATE);
                    permissions.require("knowledge.view");
                    owned(tasks.lock(planId), sourceId, "PLAN");
                    var row = draft(tasks.lock(id), sourceId, planId);
                    var latest = tasks.latestDraft(planId, row.suggestionIndex());
                    if (!"SUCCEEDED".equals(row.state()) || latest.id() != id || row.appliedDocumentId() != null) {
                        // 已更新或已采纳的建议只能回看，不创建重复草稿。
                        throw BizIllegalException.state("本次正文建议已变更，请刷新后核对");
                    }
                    var snapshot = snapshot(row);
                    // 与正式文档写入共用源锁，防止复核和保存之间改绑治理依据。
                    var origin = catalog.source(sourceId, true);
                    verify(origin, snapshot);
                    var changes = json.read(row.result(), Response.class).changes();
                    var chosen = select(changes, input.changes(), snapshot.original() == null);
                    var saved = save(origin, command, snapshot, chosen);
                    if (tasks.applied(id, Ids.parse(saved.getTargetId())) != 1) {
                        // 草稿与采纳标记在同一事务回滚。
                        throw BizIllegalException.conflict();
                    }
                    return saved;
                });
    }

    /**
     * 共用队列领取后独立调用正文模型，续查失败时记录外发未知且不重放。
     */
    void dispatch(Row row, RestClient client) {
        long id = row.id();
        try {
            var snapshot = snapshot(row);
            var rights = snapshot.original() == null
                    ? new String[] {"knowledge.view", GENERATE}
                    : new String[] {"knowledge.view", GENERATE, "knowledge.edit"};
            var model = actors.asActor(row.requestedBy(), row.authenticationVersion(), () -> {
                // 首次外发检查绑定修订、事实和原文档并发版本。
                verify(eligibleSource(row.sourceId()), snapshot);
                return models.invocation(row.modelRevisionId(), 55);
            }, rights);
            var original = snapshot.original() == null ? List.<KnowledgeDocumentVO.Section>of()
                    : snapshot.original().sections();
            var result = client.post().uri("/internal/v1/knowledge/document-drafts")
                    .body(new Request(Ids.text(id), model, snapshot.brief(), original))
                    .retrieve().body(Response.class);
            if (!valid(result, snapshot)) {
                // 模型结果只引用核对过的章节和主动治理依据。
                tasks.fail(id, "INVALID_DRAFT");
                return;
            }
            actors.asActor(row.requestedBy(), row.authenticationVersion(), () -> {
                // 在结果可读取前复核外发期间可能变化的业务依据及权限。
                verify(eligibleSource(row.sourceId()), snapshot);
                models.verifyActive(row.modelRevisionId());
                return null;
            }, rights);
            tasks.complete(id, json.write(result));
        } catch (ResourceAccessException unknown) {
            // 传输超时无法断言未发生模型请求。
            tasks.fail(id, "MODEL_RESULT_UNKNOWN");
        } catch (RestClientException failed) {
            // 业务状态不包含内部模型的 HTTP 响应。
            tasks.fail(id, "MODEL_FAILED");
        } catch (BizIllegalException changed) {
            // 撤权、来源或原文档变化均中止生成或交付。
            tasks.fail(id, "DRAFT_INPUT_UNAVAILABLE");
        } catch (RuntimeException failure) {
            // 日志只记录任务号和异常类型。
            log.warn("知识正文执行失败，task={}，异常类={}", id, failure.getClass().getSimpleName());
            tasks.fail(id, "DRAFT_FAILED");
        }
    }

    /**
     * 固定主动事实、必要依赖、核对后的文档职责及旧正文版本。
     */
    private Snapshot capture(Source source, Row plan, KnowledgePlanVO.Suggestion suggestion,
            KnowledgeDraftGenerationDTO input) {
        var prior = json.read(plan.snapshot(), KnowledgePlanningWire.Snapshot.class);
        if (!Objects.equals(source.configurationId(), prior.configuration())) {
            // 原规划连接发生变化时，重新规划并核对来源。
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "来源配置已变化，请重新规划");
        }
        var chosen = new ArrayList<Fact>();
        for (int number : new HashSet<>(input.basis()).stream().sorted().toList()) {
            // 用户只能在当前规划已展示的范围内发起生成。
            if (number > prior.facts().size()) {
                throw BizIllegalException.invalid("所选治理依据不在原规划范围内");
            }
            var fact = prior.facts().get(number - 1).fact();
            verifyFact(source, fact);
            chosen.add(factInput(fact));
        }
        var section = new Section();
        section.setFacts(chosen);
        var scope = new KnowledgeDocumentDraftDTO();
        scope.setSections(List.of(section));
        var basis = new ArrayList<Basis>();
        for (var ref : references.capture(source, scope)) {
            // 扩展自已确认定义的依赖只用于解释其真实业务含义。
            var fact = governance.require(source, new GovernanceTarget(source.id(), ref.objectId(), ref.kind(), ref.targetId()));
            if (ref.factRevisionId() != Ids.parse(fact.getRevisionId())
                    || ref.generation() != fact.getGeneration()
                    || ref.structureRevisionId() != Ids.parse(fact.getStructureRevisionId())) {
                throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "治理依据已变化，请重新核对");
            }
            basis.add(new Basis(basis.size() + 1, ref.selected(), fact));
        }
        Original original = null;
        if (suggestion.existingDocumentId() != null) {
            // 已有文档的更新以当前正文为对照，只有编辑者可同意外发。
            permissions.require("knowledge.edit");
            var current = documents.detail(source.id(), Ids.parse(suggestion.existingDocumentId()), null);
            original = new Original(current.getDocument().getId(), current.getRevisionId(),
                    current.getDocument().getVersion(), current.getContent().getSections());
        }
        var snapshot = new Snapshot(source.configurationId(), new Brief(input.title().strip(), input.purpose().strip(),
                suggestion.questions(), suggestion.exclusions(), suggestion.gaps(), basis), original);
        if (json.write(snapshot).length() > MAX_INPUT_LENGTH) {
            // 拒绝不完整外发，用户可收窄本次主动治理范围。
            throw new BizIllegalException(ErrorCode.CAPACITY_EXCEEDED, "资料超过单次正文生成容量，请收窄所选依据");
        }
        return snapshot;
    }

    /**
     * 重新核对本次实际输入，并拒绝对其他正文版本采纳更新。
     */
    private void verify(Source source, Snapshot snapshot) {
        if (!Objects.equals(source.configurationId(), snapshot.configuration())) {
            // 底层连接变更后原输入不再代表当前来源。
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "来源配置已变化");
        }
        for (var basis : snapshot.brief().facts()) {
            // 已补齐的必要依赖也必须保留实际固定修订。
            verifyFact(source, basis.fact());
        }
        if (snapshot.original() != null) {
            // 就算正文内容一样，原文档的聚合并发版本依然必须相同。
            current(source.id(), snapshot.original());
        }
    }

    /**
     * 依据源内稳定身份核查实际确认、对象世代和结构修订。
     */
    private void verifyFact(Source source, GovernanceFactVO expected) {
        var target = expected.getTarget();
        var current = governance.require(source, new GovernanceTarget(source.id(), Ids.parse(target.getObjectId()),
                target.getKind(), Ids.parse(target.getTargetId())));
        if (!expected.getRevisionId().equals(current.getRevisionId())
                || expected.getGeneration() != current.getGeneration()
                || !expected.getStructureRevisionId().equals(current.getStructureRevisionId())) {
            // 只接受原先核对的确切依据。
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "治理依据已变化，请重新核对");
        }
    }

    /**
     * 当前文档必须仍处在生成时冻结的版本。
     */
    private KnowledgeDocumentVO current(long source, Original original) {
        var document = documents.detail(source, Ids.parse(original.id()), null);
        if (!original.revisionId().equals(document.getRevisionId())
                || original.version() != document.getDocument().getVersion()) {
            // 拒绝在他人的后续修订或确认之后覆盖正文。
            throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, "原文档已变化，请重新生成更新建议");
        }
        return document;
    }

    /**
     * 按实际部署准入、可用源和治理目录检查正文生成资格。
     */
    private Source eligibleSource(long id) {
        var source = catalog.source(id, false);
        if (!"ENABLED".equals(source.lifecycle()) || source.snapshotId() == null || source.scopePending()) {
            // 无可用已采集目录时没有可发给模型的有效事实。
            throw BizIllegalException.state("当前数据源尚无可用的已采集治理目录");
        }
        if (!settings.getModelSourceIds().contains(id) || worker.getIfAvailable() == null) {
            // 用户不能通过任务请求为未经准入的源增加外发权限。
            throw BizIllegalException.state("当前数据源未启用知识模型调用");
        }
        return source;
    }

    /**
     * 拒绝缺少有效依据、伪造原章序号和结构不完整的结果。
     */
    static boolean valid(Response response, Snapshot snapshot) {
        if (response == null || response.changes() == null || response.changes().size() > MAX_SECTIONS
                || (snapshot.original() == null && response.changes().isEmpty())) {
            // 仅已有文档允许明确得到无需修改的结果。
            return false;
        }
        var replaced = new HashSet<Integer>();
        var basis = snapshot.brief().facts();
        int originalCount = snapshot.original() == null ? 0 : snapshot.original().sections().size();
        for (var change : response.changes()) {
            // 每个章节都携带可保存的正文与本轮主动事实编号。
            if (change == null || change.title() == null || change.title().isBlank() || change.title().length() > 100
                    || change.body() == null || change.body().isBlank() || change.body().length() > 6000
                    || change.basis() == null || change.basis().isEmpty() || change.basis().size() > 24
                    || new HashSet<>(change.basis()).size() != change.basis().size()
                    || change.basis().stream().anyMatch(n -> n == null || n < 1 || n > basis.size()
                            || !basis.get(n - 1).selected())) {
                return false;
            }
            Integer index = change.sectionIndex();
            if (index != null && (index < 0 || index >= originalCount || !replaced.add(index))) {
                // 每个现有章节最多有一次待采纳替换。
                return false;
            }
        }
        return true;
    }

    /**
     * 新建采纳完整草稿，更新按用户勾选的序号保留原建议次序。
     */
    private List<Change> select(List<Change> changes, List<Integer> choices, boolean create) {
        var indexes = new HashSet<>(choices);
        if (indexes.size() != choices.size() || indexes.stream().anyMatch(index -> index >= changes.size())
                || (create && indexes.size() != changes.size())) {
            // 不能采用并未在实际生成结果里展示的内容。
            throw BizIllegalException.invalid("请选择可采纳的正文建议");
        }
        return indexes.stream().sorted().map(changes::get).toList();
    }

    /**
     * 首稿复用正文草稿能力；更新时保留未选择的人工章节和原文档元数据。
     */
    private MutationVO save(Source origin, String command, Snapshot snapshot, List<Change> chosen) {
        long source = origin.id();
        var brief = snapshot.brief();
        if (snapshot.original() == null) {
            // 初稿在作者采纳前没有实际文档与确认状态。
            var body = new KnowledgeDocumentDraftDTO();
            body.setTitle(brief.title());
            body.setPurpose(brief.purpose());
            body.setQuestions(brief.questions());
            body.setExclusions(String.join("\n", brief.exclusions()));
            body.setSections(chosen.stream().map(change -> section(brief, change)).toList());
            return documents.createDraft(source, command, body);
        }
        var original = snapshot.original();
        var current = current(source, original);
        verifyRetainedBasis(origin, current, chosen);
        var content = current.getContent();
        var sections = new ArrayList<Section>();
        for (int i = 0; i < content.getSections().size(); i++) {
            // 未采纳章节仍指向用户保存的原内容及其主动治理修订。
            int index = i;
            var prior = content.getSections().get(index);
            var section = new Section();
            section.setTitle(prior.getTitle());
            section.setBody(prior.getBody());
            section.setFacts(current.getReferences().stream()
                    .filter(ref -> ref.isSelected() && ref.getSectionIndex() == index)
                    .map(ref -> factInput(ref.getTarget().getObjectId(), ref.getTarget().getKind(),
                            ref.getTarget().getTargetId(), ref.getFactRevisionId())).toList());
            sections.add(section);
        }
        for (var change : chosen) {
            // 选择替换才改写原节，新增内容追加到原章节之后。
            var section = section(brief, change);
            if (change.sectionIndex() == null) {
                sections.add(section);
            } else {
                sections.set(change.sectionIndex(), section);
            }
        }
        if (sections.size() > MAX_SECTIONS) {
            // 原文档保持不动，作者可以减少本次采纳的新增章节。
            throw BizIllegalException.invalid("采纳后正文超过十二节，请减少本次新增章节");
        }
        var body = new KnowledgeDocumentUpdateDTO();
        body.setExpectedVersion(original.version());
        body.setTitle(content.getTitle());
        body.setPurpose(content.getPurpose());
        body.setExclusions(content.getExclusions());
        body.setQuestions(content.getQuestions());
        body.setSections(sections);
        var saved = documents.saveDraft(source, Ids.parse(original.id()), command, body);
        if (saved.getVersion() == original.version()) {
            // 同文同据没有产生新的可确认修订。
            throw BizIllegalException.state("所选建议与当前正文及依据一致");
        }
        return saved;
    }

    /**
     * 未选择章节仍保留原治理修订。其必要依赖已变更时先要求人工核对，避免随部分更新静默改绑。
     */
    private void verifyRetainedBasis(Source origin, KnowledgeDocumentVO current, List<Change> chosen) {
        var replaced = new HashSet<Integer>();
        chosen.forEach(change -> replaced.add(change.sectionIndex()));
        var retained = current.getReferences().stream()
                .filter(ref -> !replaced.contains(ref.getSectionIndex()))
                .map(ref -> new Reference(ref.getSectionIndex(), origin.id(), Ids.parse(ref.getTarget().getObjectId()),
                        ref.getGeneration(), ref.getTarget().getKind(), Ids.parse(ref.getTarget().getTargetId()),
                        Ids.parse(ref.getFactRevisionId()), Ids.parse(ref.getStructureRevisionId()), ref.isSelected(),
                        ref.getLabel()))
                .toList();
        references.verify(origin, retained);
    }

    /**
     * 将模型序号映射为经过同源核对的草稿输入。
     */
    private Section section(Brief brief, Change change) {
        var section = new Section();
        section.setTitle(change.title());
        section.setBody(change.body());
        section.setFacts(change.basis().stream().map(n -> factInput(brief.facts().get(n - 1).fact())).toList());
        return section;
    }

    /**
     * 回显建议正文、对应原节和实际确认身份。
     */
    private KnowledgeDraftGenerationVO.Change view(Snapshot snapshot, Change change) {
        var original = change.sectionIndex() == null ? null : snapshot.original().sections().get(change.sectionIndex());
        var proposed = new KnowledgeDocumentVO.Section(change.title(), change.body());
        var basis = change.basis().stream().map(number -> {
            var fact = snapshot.brief().facts().get(number - 1).fact();
            var target = fact.getTarget();
            return new KnowledgePlanVO.FactReference(number, fact.getLabel(), target.getObjectId(), target.getKind(),
                    target.getTargetId(), fact.getRevisionId());
        }).toList();
        return new KnowledgeDraftGenerationVO.Change(original, proposed, basis);
    }

    /**
     * 复用文档草稿已有的引用输入模型。
     */
    private Fact factInput(GovernanceFactVO fact) {
        var target = fact.getTarget();
        return factInput(target.getObjectId(), target.getKind(), target.getTargetId(), fact.getRevisionId());
    }

    /**
     * 在草稿中保存稳定治理身份与确切修订。
     */
    private Fact factInput(String object, String kind, String target, String revision) {
        var result = new Fact();
        result.setObjectId(object);
        result.setKind(kind);
        result.setTargetId(target);
        result.setRevisionId(revision);
        return result;
    }

    /**
     * 读取作者、来源与类别均匹配的任务。
     */
    private Row owned(Row row, long source, String operation) {
        if (row == null || row.sourceId() != source || row.requestedBy() != UserContext.getUser()
                || !operation.equals(row.operation())) {
            // 隐藏其他用户、数据源和任务类型的存在。
            throw BizIllegalException.missing();
        }
        return row;
    }

    /**
     * 正文任务必须属于确切原规划。
     */
    private Row draft(Row row, long source, long plan) {
        var result = owned(row, source, "WRITE");
        if (result.parentPlanId() != plan) {
            // 不能从无关规划地址读取或采纳结果。
            throw BizIllegalException.missing();
        }
        return result;
    }

    /**
     * 读取队列中正文专用的冻结快照。
     */
    private Snapshot snapshot(Row row) {
        return json.read(row.snapshot(), Snapshot.class);
    }

    /**
     * 任务受理与人工操作记录一起提交，引用编号可由命令状态回查。
     */
    private MutationVO accepted(String command, long id, String sourceName) {
        audit.record(AuditWriteDTO.builder().actionCode(GENERATE).targetType("knowledge_draft_generation")
                .targetId(Ids.text(id)).targetName(sourceName).securityVersion(commands.auditSecurityVersion(false))
                .commandId(command).build());
        return new MutationVO(command, "knowledge_draft_generation", Ids.text(id), 1);
    }
}
