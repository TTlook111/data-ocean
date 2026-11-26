package com.dataocean.module.governance.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 只追加的治理正文和采集依据。
 */
@Data
@TableName("gov_item_revision")
public class GovernanceRevisionPO {

    /**
     * 具体内容修订的主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 本修订对应的稳定条目。
     */
    private Long itemId;

    /**
     * 条目内递增的内容版本。
     */
    private Integer revision;

    /**
     * 该修订的直接前一内容。
     */
    private Long previousRevisionId;

    /**
     * 符合表说明或字段定义契约的 JSON 文本。
     */
    private String content;

    /**
     * 人工填写或 AI 辅助来源。
     */
    private String origin;

    /**
     * 当次采用的原始建议编号。
     */
    private Long suggestionId;

    /**
     * 形成定义时的成功快照。
     */
    private Long snapshotId;

    /**
     * 形成定义时的对象结构修订。
     */
    private Long objectRevisionId;

    /**
     * 字段条目采用的列结构修订。
     */
    private Long columnRevisionId;

    /**
     * 实际保存该修订的操作者。
     */
    private Long changedBy;

    /**
     * 修订的 UTC 毫秒时刻。
     */
    private LocalDateTime createdAt;
}
