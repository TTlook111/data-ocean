package com.dataocean.module.governance.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 每个来源变化对应条目的实际办理事实。
 */
@Data
@TableName("gov_impact_task")
public class GovernanceImpactPO {

    /**
     * 定向复核事项编号。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 实际影响所属的数据源。
     */
    private Long sourceId;

    /**
     * 受影响治理条目。
     */
    private Long itemId;

    /**
     * 对应的来源结构变化。
     */
    private Long structureChangeId;

    /**
     * 对应的企业资料变化。
     */
    private Long noteChangeId;

    /**
     * 触发前该条目的历史确认修订。
     */
    private Long baselineRevisionId;

    /**
     * 新增、待复核或移除。
     */
    private String impactKind;

    /**
     * 待办理、办理中或已复核。
     */
    private String state;

    /**
     * 来源事实抵达本条目的 UTC 毫秒时刻。
     */
    private LocalDateTime createdAt;

    /**
     * 实际开始办理的 UTC 毫秒时刻。
     */
    private LocalDateTime startedAt;

    /**
     * 实际结转本笔影响的确认记录。
     */
    private Long confirmationId;

    /**
     * 本次业务审核完成时刻。
     */
    private LocalDateTime resolvedAt;

    /**
     * 是否需要后续知识处理。
     */
    private Boolean knowledgeRequired;

    /**
     * 所依赖知识处理真正完成的时间。
     */
    private LocalDateTime knowledgeCompletedAt;

    /**
     * 形成退出事实的确切知识撤下批次。
     */
    private Long knowledgeWithdrawalId;
}
