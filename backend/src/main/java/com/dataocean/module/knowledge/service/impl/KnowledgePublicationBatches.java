package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dataocean.common.Ids;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.mapper.GuardMapper;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.ReviewService;
import com.dataocean.module.knowledge.entity.dto.KnowledgeBatchPublishDTO;
import com.dataocean.module.knowledge.entity.dto.KnowledgeBatchSelectionDTO.Member;
import com.dataocean.module.knowledge.entity.vo.KnowledgePublicationBatchVO.Preview;
import com.dataocean.module.knowledge.entity.vo.KnowledgePublicationBatchVO.Result;
import com.dataocean.module.knowledge.entity.vo.KnowledgePublicationBatchVO.Rollback;
import com.dataocean.module.knowledge.entity.vo.KnowledgePublicationBatchVO.Row;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationBatchMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationBatchMapper.Batch;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper.Binding;
import com.dataocean.module.knowledge.service.KnowledgeDocumentLinks;
import com.dataocean.module.knowledge.service.KnowledgeDocumentLinks.PlannedLink;
import com.dataocean.module.knowledge.service.impl.KnowledgePublications.Checked;
import com.dataocean.module.model.service.ModelRegistry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 多份已经完成各自构建和验证的文档，共用一次完整核对和切换。
 */
@Service
@RequiredArgsConstructor
public class KnowledgePublicationBatches {

    /**
     * 组合发布还是原发布操作权限。
     */
    static final String PUBLISH = "releases.publish";

    /**
     * 同源发布和影响处理共用来源锁。
     */
    private final GovernanceCatalog catalog;

    /**
     * 单份和整批使用同一个正式切换和核实内核。
     */
    private final KnowledgePublications publications;

    /**
     * 固定配套文档引用。
     */
    private final KnowledgeDocumentLinks links;

    /**
     * 记录发布批次与实际成员。
     */
    private final KnowledgePublicationBatchMapper batches;

    /**
     * 读取实际指针与历史快照。
     */
    private final KnowledgePublicationMapper bindings;

    /**
     * 核对准确修订名称。
     */
    private final KnowledgeDocumentMapper documents;

    /**
     * 批次任务在切换事务锁定当前模型。
     */
    private final ModelRegistry models;

    /**
     * 保留当前认证和数据范围条件。
     */
    private final KnowledgeVersionUse versionUse;

    /**
     * 原核对凭据绑定全部任务及冻结输入。
     */
    private final ReviewService reviews;

    /**
     * 所有完成结果使用同一原命令。
     */
    private final MutationService commands;

    /**
     * 核实入口遵守权限变更锁顺序。
     */
    private final GuardMapper guard;

    /**
     * 保存整批提交和后续核实的审计。
     */
    private final AuditService audit;

    /**
     * 只在实际组合完整时推进来源事项。
     */
    private final KnowledgeWithdrawalProgress progress;

    /**
     * 逐条报告当前版本、目标及实际检查问题，不产生业务写入。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(long sourceId, List<Member> requested) {
        publications.requirePublisher(sourceId);
        var checked = plan(catalog.source(sourceId, false), requested);
        String token = checked.ready()
                ? reviews.preview(PUBLISH, key(sourceId), null, checked.proof(), List.of(), List.of(), List.of())
                        .getReviewToken()
                : null;
        return new Preview(checked.rows(), token);
    }

    /**
     * 整批创建实际绑定、固定引用及切换指针，任一失败均回滚。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO publish(long sourceId, String command, KnowledgeBatchPublishDTO input) {
        return commands.execute(PUBLISH, command, key(sourceId), input, null, false, false, () -> {
            publications.requirePublisher(sourceId);
            var source = catalog.source(sourceId, true);
            var checked = plan(source, input.getMembers());
            if (!checked.ready()) {
                throw BizIllegalException.state("本批发布条件已变化，请重新核对全部成员");
            }
            var proof = checked.proof();
            reviews.verify(PUBLISH, key(sourceId), null, proof, input.getReviewToken());
            models.lockCurrentBinding(
                    "rag-embedding", proof.members().get(0).basis().embeddingRevision());
            long batch = IdWorker.getId();
            String key = UUID.fromString(command).toString();
            batches.create(batch, sourceId, UserContext.getUser(), key);

            var assigned = new LinkedHashMap<Long, Long>();
            for (var member : checked.prepared()) {
                // 先建立每个实际绑定，以便同批成员互相定位到固定目标。
                long id = IdWorker.getId();
                assigned.put(member.document(), id);
                publications.insertBinding(id, member.checked().basis(), memberCommand(key, member.task()));
                batches.add(batch, id);
            }
            for (var member : checked.prepared()) {
                long id = assigned.get(member.document());
                links.pin(
                        id,
                        proof.links().get(member.document()).stream()
                                .map(link -> link.existingBinding() == null
                                        ? assigned.get(link.document())
                                        : link.existingBinding())
                                .toList());
                publications.activateBinding(
                        sourceId,
                        member.task(),
                        member.version(),
                        id,
                        member.checked(),
                        memberCommand(key, member.task()));
            }
            audit(key, Ids.text(batch), "knowledge_publication_batch", "整批发布");
            return new MutationVO(key, "knowledge_publication_batch", Ids.text(batch), 1);
        });
    }

    /**
     * 自动核实只读取实际组合，失败由批次入口继续办理。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void verifyCommitted(long source, long id) {
        guard.shared();
        catalog.source(source, true);
        if (publications.canPublish(source)) {
            confirm(source, batch(source, id));
        }
    }

    /**
     * 对结果待核实的原组合重新回读，不重发原切换。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO reconcile(long source, long id, String command) {
        return commands.execute(
                PUBLISH, command, "knowledge-batch-result:" + source + "/" + id, Ids.text(id), 1, false, false, () -> {
                    catalog.source(source, true);
                    publications.requirePublisher(source);
                    boolean verified = confirm(source, batch(source, id));
                    if (verified) {
                        audit(command, Ids.text(id), "knowledge_publication_batch", "核实整批发布结果");
                    }
                    return new MutationVO(command, "knowledge_publication_batch", Ids.text(id), verified ? 2 : 1);
                });
    }

    /**
     * 回看原发布批次与当前实际生效组合。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result result(long source, long id) {
        versionUse.requireOperator(source);
        return view(source, batch(source, id));
    }

    /**
     * 任一发布成员均可定位其原批次。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result forBinding(long source, long id) {
        versionUse.requireOperator(source);
        var binding = bindings.get(source, id);
        if (binding == null) throw BizIllegalException.missing();
        return view(source, batch(source, batches.forBinding(id)));
    }

    /**
     * 回看从这个问题版本发起、或把此版恢复为生效入口的原回退。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result rollbackForBinding(long source, long id) {
        versionUse.requireOperator(source);
        if (bindings.get(source, id) == null) {
            throw BizIllegalException.missing();
        }
        Long batchId = batches.latestRollbackBatch(source, id);
        return batchId == null ? null : view(source, batch(source, batchId));
    }

    /**
     * 回退与发布都以同一确切批次标识读取历史。
     */
    Batch batch(long source, Long id) {
        var batch = id == null ? null : batches.get(source, id);
        if (batch == null) throw BizIllegalException.missing();
        return batch;
    }

    /**
     * 读取完整历史时不忽略任何实际成员。
     */
    List<Binding> members(long source, long batch) {
        var result = batches.members(batch).stream()
                .map(id -> bindings.get(source, id))
                .toList();
        if (result.isEmpty() || result.stream().anyMatch(java.util.Objects::isNull)) {
            throw BizIllegalException.state("发布批次的固定范围不完整");
        }
        return result;
    }

    /**
     * 顺序确定地收集全批实际输入，各成员失败均留有定位。
     */
    private Plan plan(Source source, List<Member> requested) {
        var selected = requested.stream()
                .sorted(Comparator.comparingLong(item -> Ids.parse(item.taskId())))
                .toList();
        if (selected.stream().map(item -> Ids.parse(item.taskId())).distinct().count() != selected.size()) {
            throw BizIllegalException.invalid("同一个发布任务只能纳入一次");
        }
        var rows = new ArrayList<Row>();
        var prepared = new ArrayList<Frozen>();
        var revisions = new LinkedHashMap<Long, Long>();
        for (var item : selected) {
            var task = publications.task(source.id(), Ids.parse(item.taskId()));
            if (revisions.putIfAbsent(task.documentId(), task.documentRevisionId()) != null) {
                throw BizIllegalException.invalid("同一份文档在一个发布批次只能采用一个修订");
            }
            String issue = null;
            try {
                // 仍执行普通正式发布的逐题、权限及实际索引检查。
                var checked = publications.check(source, task, item.expectedVersion(), true);
                publications.verifyIndex(checked);
                prepared.add(new Frozen(task.id(), task.documentId(), item.expectedVersion(), checked));
            } catch (BizIllegalException rejected) {
                issue = rejected.getMessage();
            }
            var current = bindings.activeForDocument(source.id(), task.documentId());
            var revision = documents
                    .revision(task.documentId(), task.documentRevisionId())
                    .revision();
            rows.add(row(task.id(), task.title(), source.id(), current, null, revision, issue));
        }
        if (prepared.size() != selected.size()) {
            return new Plan(rows, null);
        }
        try {
            // 对完整组合解析依赖，任何成员都不能绕过必要引用。
            var linked = links.prepareBatch(source.id(), revisions);
            if (prepared.stream()
                            .map(item -> item.checked().basis().embeddingRevision())
                            .distinct()
                            .count()
                    != 1) {
                throw BizIllegalException.state("本批的向量配置已变化，请重新核对");
            }
            return new Plan(
                    rows,
                    new Proof(
                            prepared.stream()
                                    .map(item -> new Reviewed(
                                            item.task(),
                                            item.document(),
                                            item.version(),
                                            item.checked().basis()))
                                    .toList(),
                            linked),
                    prepared);
        } catch (BizIllegalException rejected) {
            var first = rows.get(0);
            rows.set(
                    0,
                    new Row(
                            first.taskId(),
                            first.title(),
                            first.currentBindingId(),
                            first.currentRevision(),
                            first.targetBindingId(),
                            first.targetRevision(),
                            rejected.getMessage(),
                            first.paused(),
                            null,
                            null));
            return new Plan(rows, null);
        }
    }

    /**
     * 保留组内确定的核对数据，实体和实际索引内容不放进签名素材。
     */
    private record Reviewed(long task, long document, int version, KnowledgePublications.Basis basis) {}

    /**
     * 批次固定清单与实际依赖一起签名。
     */
    private record Proof(List<Reviewed> members, Map<Long, List<PlannedLink>> links) {}

    /**
     * 确确实实通过了当前检查的候选。
     */
    private record Frozen(long task, long document, int version, Checked checked) {}

    /**
     * 检查未通过时只有逐条信息，没有确认凭据。
     */
    private record Plan(List<Row> rows, Proof proof, List<Frozen> prepared) {
        /**
         * 供受阻的预检表使用。
         */
        private Plan(List<Row> rows, Proof proof) {
            this(rows, proof, List.of());
        }

        /**
         * 只许可完全闭合的范围办理提交。
         */
        private boolean ready() {
            return proof != null;
        }
    }

    /**
     * 对实际所有成员同时核实，任何单项不明都保持整批待核实。
     */
    private boolean confirm(long source, Batch batch) {
        if (batch.verifiedAt() != null) {
            return false;
        }
        var members = members(source, batch.id());
        for (var binding : members) {
            if (!publications.canConfirm(source, publications.task(source, binding.taskId()), binding)) {
                return false;
            }
        }
        members.forEach(publications::markConfirmed);
        if (batches.verify(batch.id()) != 1) {
            throw BizIllegalException.conflict();
        }
        progress.refresh(source);
        return true;
    }

    /**
     * 用户回看时分开表达本次固定修订和现在实际生效版本。
     */
    private Result view(long source, Batch batch) {
        var rollback = batches.rolled(batch.id());
        String status = rollback == null
                ? batch.verifiedAt() == null ? "PUBLISH_RECONCILE" : "PUBLISHED"
                : rollback.verifiedAt() == null ? "ROLLBACK_RECONCILE" : "ROLLED_BACK";
        var rows = members(source, batch.id()).stream()
                .map(binding -> {
                    var target = rollback == null || binding.previousBindingId() == null
                            ? binding
                            : bindings.get(source, binding.previousBindingId());
                    var current = bindings.activeForDocument(source, binding.documentId());
                    return new Row(
                            Ids.text(binding.taskId()),
                            binding.title(),
                            current == null ? null : Ids.text(current.id()),
                            current == null ? null : current.revision(),
                            Ids.text(target.id()),
                            target.revision(),
                            null,
                            current != null && versionUse.isPaused(current.scopeId()),
                            Ids.text(binding.id()),
                            binding.revision());
                })
                .toList();
        return new Result(
                Ids.text(batch.id()),
                status,
                rows,
                rollback == null
                        ? null
                        : new Rollback(
                                Ids.text(rollback.id()),
                                Ids.text(rollback.actorId()),
                                rollback.reason(),
                                Times.instant(rollback.createdAt()),
                                Times.instant(rollback.verifiedAt())));
    }

    /**
     * 回退和发布按同一行表达实际指针及目标。
     */
    Row row(long task, String title, long source, Binding current, Binding target, Integer revision, String issue) {
        return new Row(
                Ids.text(task),
                title,
                current == null ? null : Ids.text(current.id()),
                current == null ? null : current.revision(),
                target == null ? null : Ids.text(target.id()),
                revision,
                issue,
                current != null && versionUse.isPaused(current.scopeId()),
                null,
                null);
    }

    /**
     * 防重和核对定位明确所属源。
     */
    private String key(long source) {
        return "knowledge-publish-batch:" + source;
    }

    /**
     * 原批次命令下的每个正式绑定仍有各自稳定写入编号。
     */
    private String memberCommand(String command, long task) {
        return UUID.nameUUIDFromBytes((command + ":" + task).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    /**
     * 同一事务公开记录实际批次操作。
     */
    private void audit(String command, String batch, String type, String reason) {
        audit.record(AuditWriteDTO.builder()
                .commandId(command)
                .actionCode(PUBLISH)
                .targetType(type)
                .targetId(batch)
                .targetName("发布批次")
                .reason(reason)
                .securityVersion(commands.auditSecurityVersion(false))
                .build());
    }
}
