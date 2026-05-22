package com.dataocean.module.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识审核任务实体类。
 * <p>
 * 对应 knowledge_review_task 表，记录知识文档版本的审核流程。
 * 文档版本提交审核后创建审核任务，审核人审批通过或拒绝。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_review_task")
public class KnowledgeReviewTask {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的文档版本ID */
    private Long docVersionId;

    /** 审核人用户ID */
    private Long reviewerId;

    /** 审核状态（参见 ReviewStatus 枚举） */
    private String reviewStatus;

    /** 审核意见 */
    private String reviewComment;

    /** 提交审核时间 */
    private LocalDateTime submittedAt;

    /** 审核完成时间 */
    private LocalDateTime reviewedAt;
}
