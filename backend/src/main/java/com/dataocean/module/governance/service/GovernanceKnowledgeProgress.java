package com.dataocean.module.governance.service;

import java.util.List;

/**
 * 知识模块核对实际退出范围后，治理模块负责原来源事项的办理状态。
 */
public interface GovernanceKnowledgeProgress {

    /**
     * @param id 实际待知识处理的治理复核记录
     * @param objectId 受影响的稳定来源对象
     * @param kind 治理条目类型
     * @param targetId 确切治理条目目标
     * @param baselineRevisionId 引发知识变更的原确认修订
     */
    record Target(long id, long objectId, String kind, long targetId, Long baselineRevisionId) {}

    /**
     * 读取本源已复核且尚需知识处理的实际目标。
     */
    List<Target> pendingKnowledge(long source);

    /**
     * 标记这笔知识退出的处理结果，重新汇总其所属来源事项。
     */
    void finishWithdrawal(long source, long impact, long batch);
}
