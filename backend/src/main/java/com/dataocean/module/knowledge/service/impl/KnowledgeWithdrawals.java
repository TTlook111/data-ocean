package com.dataocean.module.knowledge.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dataocean.common.Ids;
import com.dataocean.common.Times;
import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.common.exception.ErrorCode;
import com.dataocean.common.security.UserContext;
import com.dataocean.infrastructure.JsonCodec;
import com.dataocean.module.audit.entity.dto.AuditWriteDTO;
import com.dataocean.module.audit.service.AuditService;
import com.dataocean.module.datasource.service.GovernanceCatalog;
import com.dataocean.module.iam.entity.po.OutboxPO;
import com.dataocean.module.iam.entity.vo.MutationVO;
import com.dataocean.module.iam.mapper.OutboxMapper;
import com.dataocean.module.iam.service.MutationService;
import com.dataocean.module.iam.service.PermissionService;
import com.dataocean.module.iam.service.ReviewService;
import com.dataocean.module.knowledge.entity.dto.KnowledgeWithdrawalDTO;
import com.dataocean.module.knowledge.entity.vo.KnowledgeWithdrawalVO.Detail;
import com.dataocean.module.knowledge.entity.vo.KnowledgeWithdrawalVO.Member;
import com.dataocean.module.knowledge.entity.vo.KnowledgeWithdrawalVO.Preview;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper;
import com.dataocean.module.knowledge.mapper.KnowledgePublicationMapper.Binding;
import com.dataocean.module.knowledge.mapper.KnowledgeWithdrawalMapper;
import com.dataocean.module.knowledge.service.KnowledgeWithdrawalService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 仅沿当前正式修订的显式反向引用，对已核对的闭包整体退出。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeWithdrawals implements KnowledgeWithdrawalService {

    /**
     * 使用已定义的撤下动作权限。
     */
    private static final String OPERATION = "releases.withdraw";

    /**
     * 撤下与发布、文档确认使用同一个来源行锁。
     */
    private final GovernanceCatalog catalog;

    /**
     * 查询实际生效的正式引用方。
     */
    private final KnowledgePublicationMapper bindings;

    /**
     * 保留使用限制和原历史。
     */
    private final KnowledgeWithdrawalMapper withdrawals;

    /**
     * 复用源访问和绑定归属核对。
     */
    private final KnowledgeVersionUse versionUse;

    /**
     * 校验用户核对过的实际范围及过期时间。
     */
    private final ReviewService reviews;

    /**
     * 共用权威权限、命令防重与并发护卫。
     */
    private final MutationService commands;

    /**
     * 预览也需要实际业务能力。
     */
    private final PermissionService permissions;

    /**
     * 记录整批变更的操作人与原因。
     */
    private final AuditService audit;

    /**
     * 持久通知消费方所有退出绑定，和实际可用性同事务提交。
     */
    private final OutboxMapper outbox;

    /**
     * 供问数在途边界消费的明确退出身份。
     */
    private final JsonCodec json;

    /**
     * 只在全部必要事项办结时回写。
     */
    private final KnowledgeWithdrawalProgress progress;

    /**
     * 只读查询实际清单，并签发与本次显示完全一致的核对凭据。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(long source, long binding) {
        permissions.require(OPERATION);
        versionUse.requireOperator(source);
        var members = closure(versionUse.current(source, binding));
        var token = reviews.preview(OPERATION, target(source, binding), null, members, List.of(), List.of(), List.of())
                .getReviewToken();
        return new Preview(members, token);
    }

    /**
     * 来源锁内重新核对完整范围，再保存同一撤下批次。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MutationVO withdraw(long source, long binding, String command, KnowledgeWithdrawalDTO input) {
        return commands.execute(OPERATION, command, target(source, binding), input, null, false, false, () -> {
            versionUse.requireOperator(source);
            catalog.source(source, true);
            var root = versionUse.current(source, binding);
            var members = closure(root);
            try {
                // 依据变化后保留用户原输入并要求重新查看完整范围。
                reviews.verify(OPERATION, target(source, binding), null, members, input.getReviewToken());
            } catch (BizIllegalException changed) {
                if (changed.getCode() != ErrorCode.REVIEW_STALE.getCode()) {
                    throw changed;
                }
                throw new BizIllegalException(ErrorCode.REVIEW_STALE, "撤下范围或核对依据已变化，请重新核对");
            }
            long batch = IdWorker.getId();
            String reason = input.getReason().strip();
            String key = UUID.fromString(command).toString();
            withdrawals.create(batch, source, binding, reason, UserContext.getUser(), key);
            members.forEach(member -> withdrawals.add(batch, Ids.parse(member.bindingId()), member.depth()));
            progress.refresh(source);
            audit.record(AuditWriteDTO.builder()
                    .commandId(key)
                    .actionCode(OPERATION)
                    .targetType("knowledge_withdrawal")
                    .targetId(Ids.text(batch))
                    .targetName(root.title())
                    .reason(reason)
                    .securityVersion(commands.auditSecurityVersion(false))
                    .build());
            var event = new WithdrawalEvent(
                    Ids.text(source),
                    Ids.text(batch),
                    members.stream().map(Member::bindingId).toList());
            outbox.append(new OutboxPO(
                    UUID.randomUUID().toString(),
                    "knowledge.binding.withdrawn",
                    1,
                    "knowledge_withdrawal",
                    Ids.text(batch),
                    1,
                    key,
                    json.write(event),
                    "PENDING",
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));
            return new MutationVO(key, "knowledge_withdrawal", Ids.text(batch), 1);
        });
    }

    /**
     * 已撤下仍可读取历史批次和关闭时实际采用的范围。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail history(long source, long binding) {
        versionUse.requireOperator(source);
        var row = withdrawals.forBinding(source, binding);
        if (row == null) {
            throw BizIllegalException.missing();
        }
        return new Detail(
                Ids.text(row.id()),
                row.reason(),
                Ids.text(row.withdrawnBy()),
                Times.instant(row.withdrawnAt()),
                withdrawals.members(row.id()).stream()
                        .map(item ->
                                new Member(Ids.text(item.bindingId()), item.title(), item.revision(), item.depth()))
                        .toList());
    }

    /**
     * 按离目标的最短距离遍历当前有效引用方，形成确定性顺序。
     */
    private List<Member> closure(Binding root) {
        var queue = new ArrayDeque<Visit>();
        var seen = new HashSet<Long>();
        var result = new ArrayList<Member>();
        queue.add(new Visit(root, 0));
        while (!queue.isEmpty()) {
            var entry = queue.removeFirst();
            var binding = entry.binding();
            if (!seen.add(binding.id())) {
                // 同一个正式绑定可经多个引用路径触达，但整批只写一次。
                continue;
            }
            if (binding.verifiedAt() == null) {
                // 不从预览或提交中忽略待核实例，防止先撤下部分依赖。
                throw new BizIllegalException(ErrorCode.KNOWLEDGE_NOT_READY, "有引用方发布结果仍待核实，请先处理");
            }
            result.add(new Member(Ids.text(binding.id()), binding.title(), binding.revision(), entry.depth()));
            for (var dependent : bindings.referrers(root.sourceId(), binding.documentId())) {
                // 广度优先保证既直接引用又间接引用时标为直接。
                queue.add(new Visit(dependent, entry.depth() + 1));
            }
        }
        return List.copyOf(result);
    }

    /**
     * 操作凭据和命令限定同一来源及原绑定。
     */
    private String target(long source, long binding) {
        return "knowledge-withdrawal:" + source + "/" + binding;
    }

    /**
     * 实际引用遍历到的正式绑定及相对层级。
     */
    private record Visit(Binding binding, int depth) {}

    /**
     * 问数按这组权威绑定定位受影响的在途任务。
     */
    private record WithdrawalEvent(String sourceId, String withdrawalId, List<String> bindingIds) {}
}
