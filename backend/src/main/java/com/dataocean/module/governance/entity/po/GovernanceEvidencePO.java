package com.dataocean.module.governance.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 具体业务字段引用的固定出处。
 */
@Data
@TableName("gov_evidence_ref")
public class GovernanceEvidencePO {

    /**
     * 证据引用主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 证据的归属数据源。
     */
    private Long sourceId;

    /**
     * 使用证据的治理内容修订。
     */
    private Long itemRevisionId;

    /**
     * 使用证据的建议编号。
     */
    private Long suggestionId;

    /**
     * 证据支持的业务内容项。
     */
    private String contentPath;

    /**
     * 结构、资料或人工说明。
     */
    private String kind;

    /**
     * 同一条目内容内的规范引用摘要。
     */
    private String locatorKey;

    /**
     * 结构证据的成功快照。
     */
    private Long snapshotId;

    /**
     * 结构证据对应的稳定对象。
     */
    private Long objectId;

    /**
     * 结构证据的对象修订。
     */
    private Long objectRevisionId;

    /**
     * 结构证据中的稳定字段。
     */
    private Long columnId;

    /**
     * 字段的固定结构修订。
     */
    private Long columnRevisionId;

    /**
     * 资料证据的稳定资料。
     */
    private Long noteId;

    /**
     * 实际引用的资料修订。
     */
    private Long noteRevisionId;

    /**
     * 引用该修订的确切附件，可空。
     */
    private Long attachmentId;

    /**
     * 按正文章节或原件位置定位的 JSON。
     */
    private String location;

    /**
     * 实际提交的人工依据。
     */
    private String manualBasis;

    /**
     * 确认引用的操作者。
     */
    private Long createdBy;

    /**
     * 引用登记时刻，UTC 毫秒。
     */
    private LocalDateTime createdAt;
}
