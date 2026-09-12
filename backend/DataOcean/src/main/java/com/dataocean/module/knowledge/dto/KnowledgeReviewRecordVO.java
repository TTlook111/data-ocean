package com.dataocean.module.knowledge.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文档审核记录视图对象。
 * <p>
 * 对应 `knowledge_review_task` 表。该表此前只写不读（全项目无任何 Controller 暴露它），
 * 导致作者被驳回后看不到原因，无法满足开发指导 §16.3「审核拒绝后能够返回编辑并看到原因」。
 * 本 VO 是该表的对外读取形态。
 * </p>
 *
 * @author DataOcean
 */
@Data
@Builder
public class KnowledgeReviewRecordVO {

    /** 审核任务 ID */
    private Long id;

    /** 被审核的文档版本 ID */
    private Long docVersionId;

    /** 被审核的版本号 */
    private Integer versionNo;

    /** 审核状态：APPROVED / REJECTED（参见 ReviewStatus 枚举） */
    private String reviewStatus;

    /** 审核意见 */
    private String reviewComment;

    /** 审核人用户 ID */
    private Long reviewerId;

    /** 审核人姓名（查不到用户时为 null） */
    private String reviewerName;

    /** 提交审核时间 */
    private LocalDateTime submittedAt;

    /** 审核完成时间 */
    private LocalDateTime reviewedAt;
}
