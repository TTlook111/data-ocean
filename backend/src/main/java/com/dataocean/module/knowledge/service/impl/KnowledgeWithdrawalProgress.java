package com.dataocean.module.knowledge.service.impl;

import com.dataocean.module.governance.service.GovernanceKnowledgeProgress;
import com.dataocean.module.knowledge.mapper.KnowledgeWithdrawalMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 以来源事项的实际治理目标核对是否仍有文档或候选在办理。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeWithdrawalProgress {

    /**
     * 知识侧核对真实退出与仍需处理的引用。
     */
    private final KnowledgeWithdrawalMapper withdrawals;

    /**
     * 来源事项由治理服务汇总。
     */
    private final GovernanceKnowledgeProgress progress;

    /**
     * 与本源权威状态在同一事务内完成，不把任一首篇退出当作整体办结。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refresh(long source) {
        for (var target : progress.pendingKnowledge(source)) {
            Long batch = withdrawals.withdrawalFor(source, target);
            if (batch != null && !withdrawals.pendingKnowledge(source, target)) {
                // 有实际退出证据且所有必要文档、任务均已处理。
                progress.finishWithdrawal(source, target.id(), batch);
            }
        }
    }
}
