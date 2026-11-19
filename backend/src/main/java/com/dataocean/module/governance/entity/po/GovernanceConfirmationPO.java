package com.dataocean.module.governance.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 人工确认和移除处理的不可变审核记录。
 */
@Data
@TableName("gov_confirmation")
public class GovernanceConfirmationPO {

    /**
     * 确认记录的主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 经审核的实际条目。
     */
    private Long itemId;

    /**
     * 当时获确认的业务修订或移除前业务修订。
     */
    private Long itemRevisionId;

    /**
     * 业务确认或确认移除。
     */
    private String action;

    /**
     * 审核当时核对的成功快照。
     */
    private Long snapshotId;

    /**
     * 审核绑定的对象结构修订。
     */
    private Long objectRevisionId;

    /**
     * 审核绑定的字段结构修订。
     */
    private Long columnRevisionId;

    /**
     * 成功审核的原命令 UUID。
     */
    private String commandId;

    /**
     * 实际操作者。
     */
    private Long confirmedBy;

    /**
     * 实际发生审核的 UTC 毫秒时刻。
     */
    private LocalDateTime confirmedAt;
}
