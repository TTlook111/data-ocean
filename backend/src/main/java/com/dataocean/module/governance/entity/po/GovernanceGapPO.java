package com.dataocean.module.governance.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 关键业务缺口和核清记录。
 */
@Data
@TableName("gov_gap")
public class GovernanceGapPO {

    /**
     * 具体核对项编号。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所影响的实际治理条目。
     */
    private Long itemId;

    /**
     * 提出问题时打开的业务修订。
     */
    private Long foundRevisionId;

    /**
     * 产生具体待核对问题的模型建议。
     */
    private Long suggestionId;

    /**
     * 需要人工处理的业务内容项。
     */
    private String contentPath;

    /**
     * 有实际影响的疑问。
     */
    private String question;

    /**
     * AI 建议或人工要求补充。
     */
    private String origin;

    /**
     * 开放或已核清。
     */
    private String state;

    /**
     * 写入结论的业务内容修订。
     */
    private Long resolvedRevisionId;

    /**
     * 人工核清结论。
     */
    private String resolution;

    /**
     * 登记缺口的操作者。
     */
    private Long openedBy;

    /**
     * 登记的 UTC 毫秒时刻。
     */
    private LocalDateTime openedAt;

    /**
     * 提交核清结论的操作者。
     */
    private Long resolvedBy;

    /**
     * 实际核清的 UTC 毫秒时刻。
     */
    private LocalDateTime resolvedAt;
}
