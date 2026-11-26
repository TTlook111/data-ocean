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
import com.dataocean.module.knowledge.service.KnowledgeVersionService;
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
public class KnowledgeVersionUse implements KnowledgeVersionService {
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
    @Override
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
            String reason = input.getReason().strip();
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
     * 新问数及重新引用对话上下文时，只核对当前生效的绑定。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, noRollbackFor = BizIllegalException.class)
    public Binding requireForQuery(long sourceId, long id) {
        requireQueryAccess(sourceId);
        return requireQueryableBinding(sourceId, id);
    }

    /**
     * 原轮次的真实绑定可跨普通切版续作，来源授权和实时使用限制继续生效。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, noRollbackFor = BizIllegalException.class)
    public Binding requirePinnedForQuery(long sourceId, long id) {
        requireQueryAccess(sourceId);

        var source = catalog.source(sourceId, false);
        var binding = binding(sourceId, id);
        requireRetainedBasis(source, binding);
        links.verifyRetainedPinned(binding, dependency -> {
            // 继续核对原先固定的依赖组合，不引入新版本的事实。
            requireRetainedBasis(source, dependency);
        });
        return binding;
    }

    /**
     * 当前账号的问数及来源权限优先于任何历史快照。
     */
    private void requireQueryAccess(long sourceId) {
        permissions.require("query.view");
        permissions.require("query.ask");
        access.requireSource(UserContext.getUser(), sourceId);
    }

    /**
     * 以反馈记录中的复验人复核查询资格，结案人的会话仅用于结案授权。
     */
    @Override
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
    Binding requireQueryableBinding(long sourceId, long id) {
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
    @Override
    public Binding current(long source, long id) {
        var binding = binding(source, id);
        if (!Objects.equals(binding.activeBindingId(), binding.id())) {
            // 新动作以当前入口为准，续作通过固定组合入口单独复核。
            throw BizIllegalException.state("当前生效版本已变化或尚未完成核实");
        }
        requireVerifiedBinding(binding);
        return binding;
    }

    /**
     * 用来源和绑定编号共同定位发布事实。
     */
    private Binding binding(long source, long id) {
        var binding = bindings.get(source, id);
        if (binding == null) {
            // 旧产物清理或来源不符时结束续作。
            throw BizIllegalException.missing();
        }
        return binding;
    }

    /**
     * 无论运行是否已冻结，只有可用的正式版本才能使用。
     */
    private void requireVerifiedBinding(Binding binding) {
        if (binding.verifiedAt() == null || batches.rollbackPending(binding.scopeId())) {
            // 发布或回退尚未核实，保持范围限制。
            throw BizIllegalException.state("当前生效版本已变化或尚未完成核实");
        }
        if (isWithdrawn(binding.id())) {
            // 主动退出的知识不因旧请求仍在运行而恢复。
            throw BizIllegalException.state("该正式知识已撤下");
        }
    }

    /**
     * 同一范围必须仍有已核实的生效入口，旧绑定的自身条件也须逐项复核。
     */
    private void requireRetainedBasis(Source source, Binding binding) {
        requireVerifiedBinding(binding);
        if (binding.activeBindingId() == null || isPaused(binding.scopeId())) {
            // 无生效入口或范围已暂停，不能靠历史版本继续使用。
            throw BizIllegalException.state("受影响的正式知识未生效或已暂停");
        }
        if (!Objects.equals(binding.activeBindingId(), binding.id())) {
            // 普通切换应有该范围内已核实的当前入口。
            var active = current(source.id(), binding.activeBindingId());
            if (active.scopeId() != binding.scopeId()) {
                // 指针异常时无法证明这次替换仍属于相同范围。
                throw BizIllegalException.state("正式知识的当前生效范围已变化");
            }
        }
        requireOwnBasis(source, binding);
    }

    /**
     * 恢复验证可以针对手动暂停的范围，其他治理禁止和结构或模型限制依然生效。
     */
    @Override
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
        if (batches.rollbackPending(binding.scopeId())) {
            // 当前回退仍待核实时保留范围限制。
            throw BizIllegalException.state("当前来源不可用或治理范围尚未核实");
        }
        requireSourceBasis(source);
        models.verifyActive(binding.embeddingRevisionId());
        requireGovernanceBasis(source, binding);
    }

    /**
     * 核对当前目录的可用条件，由正式使用和已证明的历史读取共用。
     */
    void requireSourceBasis(Source source) {
        if (!"ENABLED".equals(source.lifecycle())
                || source.scopePending()
                || source.snapshotId() == null
                || source.configurationId() == null) {
            throw BizIllegalException.state("当前来源不可用或治理范围尚未核实");
        }
    }

    /**
     * 复核绑定的冻结引用，历史统计只在相同治理事实仍可证明时复用。
     */
    void requireGovernanceBasis(Source source, Binding binding) {
        references.verify(source, documents.references(binding.documentRevisionId()));
    }

    /**
     * 暂停随发布范围生效，即使同一范围切换正式版本也保持限制。
     */
    @Override
    public boolean isPaused(long scope) {
        return pauses.isPaused(scope);
    }

    /**
     * 已撤下只影响历史中确切的绑定。
     */
    @Override
    public boolean isWithdrawn(long binding) {
        return withdrawals.withdrawn(binding);
    }

    /**
     * 使用控制的表单和写操作同时核验页面、正文及源访问。
     */
    @Override
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
