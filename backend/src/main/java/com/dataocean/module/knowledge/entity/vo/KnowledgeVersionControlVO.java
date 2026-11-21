package com.dataocean.module.knowledge.entity.vo;

import java.time.Instant;
import java.util.List;

/**
 * 当前正式版本的暂停状态、恢复验证和原历史记录。
 * @param active 当前范围尚未解除的暂停
 * @param recovery 当前绑定的验证状态及下一步
 * @param history 暂停与显式解除的履历
 */
public record KnowledgeVersionControlVO(Pause active, Recovery recovery, List<Pause> history) {

    /**
     * @param id 暂停记录编号
     * @param bindingId 实际办理暂停的版本
     * @param reason 实际暂停原因
     * @param pausedBy 办理暂停的人
     * @param pausedAt 暂停提交时间
     * @param runId 暂停期间最近一轮验证的编号
     * @param runBindingId 该轮验证采用的绑定，用于恢复历史的确切入口
     * @param restoreReason 恢复时填写的风险消除依据
     * @param restoredBy 显式恢复的人
     * @param restoredAt 显式解除时间
     */
    public record Pause(
            String id,
            String bindingId,
            String reason,
            String pausedBy,
            Instant pausedAt,
            String runId,
            String runBindingId,
            String restoreReason,
            String restoredBy,
            Instant restoredAt) {}

    /**
     * @param state 当前恢复验证状态
     * @param runId 最近一轮运行编号
     * @param issue 当前阻断原因或已完成证据的失效原因
     * @param canRun 当前绑定的依据允许发起新运行
     */
    public record Recovery(String state, String runId, String issue, boolean canRun) {}

    /**
     * @param taskId 正式绑定对应的原发布任务
     * @param taskVersion 人工裁定采用的真实任务版本
     * @param pauseId 验证针对的确切暂停
     * @param current 版本上仍指向此轮，且验证依据仍匹配
     * @param validation 可阅读的逐题结果及历史
     */
    public record RunDetail(
            String taskId, int taskVersion, String pauseId, boolean current, KnowledgeValidationVO validation) {}
}
