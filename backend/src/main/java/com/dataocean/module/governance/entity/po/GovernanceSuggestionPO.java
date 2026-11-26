package com.dataocean.module.governance.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 该任务落库的实际模型建议。
 */
@Data
@TableName("gov_suggestion")
public class GovernanceSuggestionPO {

    /**
     * 建议实际编号。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 生成建议的原任务。
     */
    private String requestId;

    /**
     * 构成建议的实际目标。
     */
    private Long itemId;

    /**
     * 真实模型调用快照。
     */
    private Long modelCallId;

    /**
     * 实际建议的明确业务内容。
     */
    private String content;

    /**
     * 保存 UTC 时刻。
     */
    private LocalDateTime createdAt;
}
