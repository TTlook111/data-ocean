package com.dataocean.module.knowledge.service.impl;

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
import com.dataocean.module.iam.entity.po.OutboxPO;
import com.dataocean.module.iam.entity.vo.ChangeVO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.mapper.OutboxMapper;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ReviewService;
import com.dataocean.module.knowledge.entity.dto.KnowledgeBatchRollbackDTO;
import com.dataocean.module.knowledge.entity.vo.KnowledgePublicationBatchVO.Preview;
import com.dataocean.module.knowledge.entity.vo.KnowledgePublicationBatchVO.Row;
import com.dataocean.module.knowledge.mapper.KnowledgeBuildMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationBatchMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationBatchMapper.Batch;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper.Binding;
import com.dataocean.module.knowledge.service.KnowledgeDocumentLinks;
import com.dataocean.module.knowledge.service.KnowledgeReferences;
import com.dataocean.module.model.service.ModelRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 只在目标组合真实可核对时原子退回整批的直接上一版。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeRollbacks {

    /**
     * 回退有独立业务权限。
     */
    private static final String ACTION = "versions.rollback";

    /**
     * 回退与发布使用同一来源锁。
     */
    private final GovernanceCatalog catalog;

    /**
     * 取得原发布批次范围。
     */
    private final KnowledgePublicationBatches publications;

    /**
     * 读取实际入口及确切历史。
     */
    private final KnowledgePublicationMapper bindings;

    /**
     * 原回退及当前核实阶段。
     */
    private final KnowledgePublicationBatchMapper batches;

    /**
     * 准确读取固定目标候选和构建。
     */
    private final KnowledgeBuildMapper builds;

    /**
     * 取得目标版本的确切治理依据。
     */
    private final KnowledgeDocumentMapper documents;

    /**
     * 当前治理结构检查保留既有使用限制。
     */
    private final KnowledgeReferences references;

    /**
     * 直接上一版仍须拥有可信关键题验证。
     */
    private final KnowledgeValidations validations;

    /**
     * 恢复组合按原来冻结的文档依赖解析。
     */
    private final KnowledgeDocumentLinks links;

    /**
     * 当前角色、来源范围与撤下事实。
     */
    private final KnowledgeVersionUse versionUse;

    /**
     * 核对当前权威模型及实际索引。
     */
    private final ModelRegistry models;

    /**
     * 真实索引核实边界，保持原正式版本和索引不变。
     */
    private final KnowledgeIndexReadback indexes;

    /**
     * 范围或权限改变后原核对凭据失效。
     */
    private final ReviewService reviews;

    /**
     * 回退命令的防重与实际提交确认。
     */
    private final MutationService commands;

    /**
     * 回退预检也需实际业务权限。
     */
    private final PermissionService permissions;

    /**
     * 自动核实时继续以一致顺序检查身份。
     */
    private final GuardMapper guard;

    /**
     * 保存回退原因、范围及核实。
     */
    private final AuditService audit;

    /**
     * 可靠通知询问入口回退已核实。
     */
    private final OutboxMapper outbox;

    /**
     * 事件仅保存确切身份。
     */
    private final JsonCodec json;

    /**
     * 逐条回读实际当前和直接上一版，长于原范围的修改必须阻断。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(long source, long id) {
        require(source);
        var plan = plan(catalog.source(source, false), publications.batch(source, id));
        String token = plan.proof() == null
                ? null
                : reviews.preview(ACTION, key(source, id), null, plan.proof(), List.of(), List.of(), List.of())
                        .getReviewToken();
        return new Preview(plan.rows(), token);
    }

    /**
     * 原子切换整个批次，待核实期间保留已提交的结果及现行限制。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO rollback(long source, long batch, String command, KnowledgeBatchRollbackDTO input) {
        return commands.execute(ACTION, command, key(source, batch), input, null, false, false, () -> {
            require(source);
            var origin = catalog.source(source, true);
            var checked = plan(origin, publications.batch(source, batch));
            if (checked.proof() == null) {
                // 整批预检发生变化时不尝试部分提交。
                String issue = checked.rows().stream()
                        .map(Row::issue)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse("请重新核对回退范围");
                throw new BizIllegalException(ErrorCode.DEPENDENCY_STALE, issue);
            }
            var proof = checked.proof();
            reviews.verify(ACTION, key(source, batch), null, proof, input.reviewToken());
            models.lockCurrentBinding("rag-embedding", proof.embedding());
            long id = IdWorker.getId();
            String key = UUID.fromString(command).toString();
            String reason = input.reason().strip();
            batches.rollback(id, batch, UserContext.getUser(), key, reason);
            for (var transition : proof.transitions()) {
                // 实际比较基础及代数，任一并发冲突由外层事务回滚全部成员。
                if (bindings.switchBinding(
                                source,
                                transition.scope(),
                                transition.current(),
                                transition.epoch(),
                                transition.target())
                        != 1) {
                    throw BizIllegalException.conflict();
                }
            }
            var changes = checked.rows().stream()
                    .map(row -> new ChangeVO(
                            row.taskId(), row.title(), "r" + row.currentRevision(), "r" + row.targetRevision()))
                    .toList();
            audit(key, id, reason, changes);
            return new MutationVO(key, "knowledge_rollback", Ids.text(id), 1);
        });
    }

    /**
     * 初次响应仅尝试核实持久事实，回包中断可以重新查询。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void verifyCommitted(long source, long batch, long id) {
        guard.shared();
        var origin = catalog.source(source, true);
        try {
            require(source);
        } catch (BizIllegalException denied) {
            // 失权时由其他合法操作者接手核实。
            return;
        }
        publications.batch(source, batch);
        var record = batches.rolled(batch);
        if (record != null && record.id() == id) {
            confirm(origin, batch, record);
        }
    }

    /**
     * 提交后不明确的实际结果只核对原记录，不重放切换。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO reconcile(long source, long batch, String command) {
        return commands.execute(
                ACTION,
                command,
                "knowledge-rollback-result:" + source + "/" + batch,
                Ids.text(batch),
                1,
                false,
                false,
                () -> {
                    require(source);
                    var origin = catalog.source(source, true);
                    publications.batch(source, batch);
                    var record = batches.rolled(batch);
                    if (record == null) {
                        throw BizIllegalException.state("当前批次没有已提交的回退");
                    }
                    boolean verified = confirm(origin, batch, record);
                    if (verified) {
                        audit(command, record.id(), "核实整批回退结果", List.of());
                    }
                    return new MutationVO(command, "knowledge_rollback", Ids.text(record.id()), verified ? 2 : 1);
                });
    }

    /**
     * 按同一历史快照准备精确目标，逐项显示阻断原因。
     */
    private Plan plan(Source source, Batch batch) {
        var members = publications.members(source.id(), batch.id());
        var wanted = new LinkedHashMap<Long, Long>();
        for (var member : members) {
            if (member.previousBindingId() != null) {
                wanted.put(member.documentId(), member.previousBindingId());
            }
        }
        var rows = new ArrayList<Row>();
        var transitions = new ArrayList<Transition>();
        String batchIssue = null;
        Long model = null;
        try {
            if (batches.rolled(batch.id()) != null) {
                throw BizIllegalException.state("本批已提交回退，请回看原记录");
            }
            if (batch.verifiedAt() == null) {
                throw BizIllegalException.state("原发布结果仍待核实");
            }
            model = models.requireBinding("rag-embedding");
        } catch (BizIllegalException rejected) {
            batchIssue = rejected.getMessage();
        }
        for (var member : members) {
            var current = bindings.activeForDocument(source.id(), member.documentId());
            var target =
                    member.previousBindingId() == null ? null : bindings.get(source.id(), member.previousBindingId());
            String issue = batchIssue;
            if (issue == null) {
                try {
                    // 基础改变、目标不完整、依赖或任何证据不适配均阻断整批。
                    if (current == null || current.id() != member.id()) {
                        throw BizIllegalException.state("该成员已被后续发布替换，不能覆盖当前生效版本");
                    }
                    if (target == null || target.scopeId() != member.scopeId()) {
                        throw BizIllegalException.state("该成员没有同范围的直接上一版");
                    }
                    if (versionUse.isWithdrawn(current.id()) || batches.rollbackPending(current.scopeId())) {
                        throw BizIllegalException.state("当前绑定已撤下或仍在核实切换结果");
                    }
                    for (var dependent : bindings.referrers(source.id(), member.documentId())) {
                        if (!wanted.containsKey(dependent.documentId()) && links.pins(dependent, member.id())) {
                            throw BizIllegalException.state("存在批次外的必要引用方，需要先完成其范围处理");
                        }
                    }
                    compatible(source, target, model);
                    links.verifyProjected(target, wanted, dependency -> {
                        // 依赖的当前兼容性同样检查，保留其暂停及禁止。
                        compatible(source, dependency, target.embeddingRevisionId());
                    });
                    validations.requireRollbackEvidence(source, target);
                    verifyIndex(source.id(), target);
                    transitions.add(new Transition(member.scopeId(), current.bindingEpoch(), member.id(), target.id()));
                } catch (BizIllegalException rejected) {
                    issue = rejected.getMessage();
                }
            }
            rows.add(publications.row(
                    member.taskId(),
                    member.title(),
                    source.id(),
                    current,
                    target,
                    target == null ? null : target.revision(),
                    issue));
        }
        return new Plan(
                rows,
                transitions.size() == members.size() ? new Proof(source.configurationId(), model, transitions) : null);
    }

    /**
     * 使用当前目录比对目标基于的治理事实和物理模型，现行暂停禁止不需要解除。
     */
    private void compatible(Source source, Binding target, long model) {
        if (!"ENABLED".equals(source.lifecycle())
                || source.scopePending()
                || source.snapshotId() == null
                || source.configurationId() == null) {
            throw BizIllegalException.state("当前来源或结构范围不可用于核对");
        }
        if (target.verifiedAt() == null
                || versionUse.isWithdrawn(target.id())
                || batches.rollbackPending(target.scopeId())) {
            throw BizIllegalException.state("目标版本已撤下或尚无确切核实结果");
        }
        if (target.embeddingRevisionId() != model) {
            throw BizIllegalException.state("直接上一版缺少与当前向量配置匹配的索引");
        }
        models.verifyActive(model);
        references.verifyRollbackBasis(source, documents.references(target.documentRevisionId()));
    }

    /**
     * 只核验正式绑定对应的实际索引和模型。
     */
    private void verifyIndex(long source, Binding target) {
        var candidate = builds.candidate(source, target.candidateId());
        var build = builds.get(target.buildId());
        if (candidate == null
                || build == null
                || !"SUCCEEDED".equals(build.state())
                || build.candidateId() != target.candidateId()
                || !Objects.equals(candidate.getDocumentRevisionId(), target.documentRevisionId())
                || !Objects.equals(candidate.getEmbeddingRevisionId(), target.embeddingRevisionId())) {
            throw BizIllegalException.state("直接上一版尚无可信索引代次");
        }
        indexes.verify(build, target.documentRevisionId(), target.embeddingRevisionId(), candidate.getFingerprint());
    }

    /**
     * 整批实际指针和索引都一致才标成功，入口同时恢复正常的问数门禁判断。
     */
    private boolean confirm(Source source, long batch, KnowledgePublicationBatchMapper.Rollback record) {
        if (record.verifiedAt() != null) {
            return false;
        }
        var members = publications.members(source.id(), batch);
        var targets = new ArrayList<String>();
        try {
            long model = models.requireBinding("rag-embedding");
            for (var member : members) {
                var actual = bindings.activeForDocument(source.id(), member.documentId());
                if (actual == null
                        || !Objects.equals(member.previousBindingId(), actual.id())
                        || actual.embeddingRevisionId() != model) {
                    // 部分切换或另一个实际入口均继续保留待核实。
                    return false;
                }
                verifyIndex(source.id(), actual);
                targets.add(Ids.text(actual.id()));
            }
            models.lockCurrentBinding("rag-embedding", model);
        } catch (BizIllegalException unavailable) {
            return false;
        }
        if (batches.verifyRollback(record.id()) != 1) {
            throw BizIllegalException.conflict();
        }
        var event = new RollbackEvent(Ids.text(source.id()), Ids.text(batch), Ids.text(record.id()), targets);
        outbox.append(new OutboxPO(
                UUID.randomUUID().toString(),
                "knowledge.batch.rolled-back",
                1,
                "knowledge_rollback",
                Ids.text(record.id()),
                2,
                null,
                json.write(event),
                "PENDING",
                0,
                null,
                null,
                null,
                null,
                null,
                null));
        return true;
    }

    /**
     * 回退仅向具有现行源和查看资格的操作人开放。
     */
    private void require(long source) {
        permissions.require(ACTION);
        versionUse.requireOperator(source);
    }

    /**
     * 保存回退范围的每个实际前后版本、原因及原操作者。
     */
    private void audit(String command, long id, String reason, List<ChangeVO> changes) {
        audit.record(AuditWriteDTO.builder()
                .commandId(command)
                .actionCode(ACTION)
                .targetType("knowledge_rollback")
                .targetId(Ids.text(id))
                .targetName("整批回退")
                .reason(reason)
                .changes(changes)
                .securityVersion(commands.auditSecurityVersion(false))
                .build());
    }

    /**
     * 防重和签名固定源及原始发布批次。
     */
    private String key(long source, long batch) {
        return "knowledge-rollback:" + source + "/" + batch;
    }

    /**
     * 权威回退输入固定前后指针及不能重放的入口代数。
     */
    private record Transition(long scope, long epoch, long current, long target) {}

    /**
     * 完整组合的明确核对清单。
     */
    private record Proof(long configuration, long embedding, List<Transition> transitions) {}

    /**
     * 受阻的行也会显示，只有全部符合才包含提交素材。
     */
    private record Plan(List<Row> rows, Proof proof) {}

    /**
     * 通知实际核实的回退组合。
     */
    private record RollbackEvent(String sourceId, String batchId, String rollbackId, List<String> bindingIds) {}
}
