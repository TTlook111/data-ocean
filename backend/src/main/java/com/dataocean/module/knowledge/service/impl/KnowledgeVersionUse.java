package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dataocean.common.Ids;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.security.UserContext;
import com.dataocean.module.access.service.AccessScopeService;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.datasource.service.GovernanceCatalog.Source;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.knowledge.entity.dto.KnowledgeVersionPauseDTO;
import com.dataocean.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationBatchMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper.Binding;
import com.dataocean.module.knowledge.mapper.KnowledgeVersionPauseMapper;
import com.dataocean.module.knowledge.mapper.KnowledgeWithdrawalMapper;
import com.dataocean.module.knowledge.service.KnowledgeDocumentLinks;
import com.dataocean.module.knowledge.service.KnowledgeReferences;
import com.dataocean.module.model.service.ModelRegistry;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 使用范围的限制优先于正式绑定及历史索引。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeVersionUse {
    /**
     * 同一个来源锁序列化发布和使用控制。
     */
    private final GovernanceCatalog catalog;

    /**
     * 权威绑定用于核对作用范围及当前生效。
     */
    private final KnowledgePublicationMapper bindings;

    /**
     * 回退待核实的入口继续受限。
     */
    private final KnowledgePublicationBatchMapper batches;

    /**
     * 获取并保留与绑定切换独立的使用限制。
     */
    private final KnowledgeVersionPauseMapper pauses;

    /**
     * 原绑定保留可回看，退出事实独立于暂停。
     */
    private final KnowledgeWithdrawalMapper withdrawals;

    /**
     * 问数须核对实际冻结的文档组合。
     */
    private final KnowledgeDocumentLinks links;

    /**
     * 复核文档固定的治理依赖。
     */
    private final KnowledgeDocumentMapper documents;

    /**
     * 当前引用仍须遵守对象级禁止。
     */
    private final KnowledgeReferences references;

    /**
     * 索引所使用的模型修订应仍可使用。
     */
    private final ModelRegistry models;

    /**
     * 审计和防重由公共命令边界完成。
     */
    private final MutationService commands;

    /**
     * 记录独立的暂停操作轨迹。
     */
    private final AuditService audit;

    /**
     * 发起人同时应具备版本页面及原始正文访问。
     */
    private final PermissionService permissions;

    /**
     * 业务库访问资格继续使用现行授权。
     */
    private final AccessScopeService access;

    /**
     * 暂停整个当前单文档范围，保留原版本和索引。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO pause(long source, long id, String command, KnowledgeVersionPauseDTO input) {
        return commands.execute("versions.pause", command, target(source, id), input, null, false, false, () -> {
            // 使用控制与发布共享来源锁，拒绝对已替换的详情提交。
            requireOperator(source);
            catalog.source(source, true);
            var binding = current(source, id);
            if (isPaused(binding.scopeId())) {
                throw BizIllegalException.state("该范围已暂停，请重新查看当前状态");
            }
            long pause = IdWorker.getId();
            String reason = input.reason().strip();
            pauses.create(pause, binding.scopeId(), binding.id(), reason, UserContext.getUser());
            audit.record(AuditWriteDTO.builder()
                    .commandId(command)
                    .actionCode("versions.pause")
                    .targetType("knowledge_version_pause")
                    .targetId(Ids.text(pause))
                    .targetName(binding.title())
                    .reason(reason)
                    .securityVersion(commands.auditSecurityVersion(false))
                    .build());
            return new MutationVO(command, "knowledge_version_pause", Ids.text(pause), 1);
        });
    }

    /**
     * 正式问数使用绑定前及在途重新校验时通过同一权威入口。
     * 反馈回看会捕获旧绑定等业务拒绝并裁剪结果，拒绝仍向调用方抛出，不将外层事务标记为回滚。
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, noRollbackFor = BizIllegalException.class)
    public Binding requireForQuery(long sourceId, long id) {
        permissions.require("query.view");
        permissions.require("query.ask");
        access.requireSource(UserContext.getUser(), sourceId);
        return requireQueryableBinding(sourceId, id);
    }

    /**
     * 以反馈记录中的复验人复核查询资格，结案人的会话仅用于结案授权。
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, noRollbackFor = BizIllegalException.class)
    public void requireForFeedbackReplay(long verifier, long sourceId, long id) {
        var codes = permissions.effective(verifier);
        if (!codes.contains("query.view")
                || !codes.contains("query.ask")
                || access.findSource(verifier, sourceId).isEmpty()) {
            // 撤权后旧的只读问数不能继续作为结案依据。
            throw BizIllegalException.denied();
        }
        requireQueryableBinding(sourceId, id);
    }

    /**
     * 正式问数和源数据复验共用当前发布、暂停及依赖检查。
     */
    private Binding requireQueryableBinding(long sourceId, long id) {
        var source = catalog.source(sourceId, false);
        var binding = current(sourceId, id);
        if (isPaused(binding.scopeId())) {
            // 普通问数没有隔离恢复验证资格。
            throw BizIllegalException.state("受影响的正式知识已暂停使用");
        }
        requireBasis(source, binding);
        return binding;
    }

    /**
     * 办理和续作检查路径来源及同一实际在用绑定。
     */
    public Binding current(long source, long id) {
        var binding = bindings.get(source, id);
        if (binding == null) {
            throw BizIllegalException.missing();
        }
        if (!Objects.equals(binding.activeBindingId(), binding.id())
                || binding.verifiedAt() == null
                || batches.rollbackPending(binding.scopeId())) {
            // 历史绑定及结果尚未核实的版本不能参与办理。
            throw BizIllegalException.state("当前生效版本已变化或尚未完成核实");
        }
        if (isWithdrawn(id)) {
            // 行为退出后只保留历史读取，不能发起恢复验证或参与问数。
            throw BizIllegalException.state("该正式知识已撤下");
        }
        return binding;
    }

    /**
     * 恢复验证可以针对手动暂停的范围，其他治理禁止和结构或模型限制依然生效。
     */
    public void requireBasis(Source source, Binding binding) {
        requireOwnBasis(source, binding);
        requireDependencies(source, binding);
    }

    /**
     * 从问数和恢复验证共用的绑定核对保护所需文档。
     */
    public void requireDependencies(Source source, Binding binding) {
        links.verifyPinned(binding, dependency -> {
            // 只有同组实际在用且当前可使用的依赖才能进入运行。
            if (isPaused(dependency.scopeId())) {
                throw BizIllegalException.state("必要引用的正式知识已暂停");
            }
            requireOwnBasis(source, dependency);
        });
    }

    /**
     * 复核这一固定版本的模型和治理事实。
     */
    private void requireOwnBasis(Source source, Binding binding) {
        if (batches.rollbackPending(binding.scopeId())
                || !"ENABLED".equals(source.lifecycle())
                || source.scopePending()
                || source.snapshotId() == null
                || source.configurationId() == null) {
            throw BizIllegalException.state("当前来源不可用或治理范围尚未核实");
        }
        models.verifyActive(binding.embeddingRevisionId());
        references.verify(source, documents.references(binding.documentRevisionId()));
    }

    /**
     * 暂停随发布范围生效，即使同一范围切换正式版本也保持限制。
     */
    public boolean isPaused(long scope) {
        return pauses.isPaused(scope);
    }

    /**
     * 已撤下只影响历史中确切的绑定。
     */
    public boolean isWithdrawn(long binding) {
        return withdrawals.withdrawn(binding);
    }

    /**
     * 使用控制的表单和写操作同时核验页面、正文及源访问。
     */
    public void requireOperator(long source) {
        permissions.require("releases.view");
        permissions.require("knowledge.view");
        access.requireSource(UserContext.getUser(), source);
    }

    /**
     * 防重命令归属明确的来源与生效绑定。
     */
    private String target(long source, long id) {
        return "knowledge-version:" + source + "/" + id;
    }
}
