package com.dataocean.module.fieldtag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 反馈审核实体类
 * <p>
 * 记录管理员对负向反馈的审核结果。
 * 审核通过后触发可信度扣分，审核驳回则不调整可信度。
 * </p>
 */
@Data
@TableName("feedback_review")
public class FeedbackReview {

    /** 审核状态：待审核 */
    public static final String STATUS_PENDING = "PENDING";
    /** 审核状态：已通过 */
    public static final String STATUS_APPROVED = "APPROVED";
    /** 审核状态：已驳回 */
    public static final String STATUS_REJECTED = "REJECTED";

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的用户反馈ID */
    private Long feedbackId;

    /** 审核状态：PENDING/APPROVED/REJECTED */
    private String reviewStatus;

    /** 审核人用户ID */
    private Long reviewerId;

    /** 审核意见 */
    private String reviewComment;

    /** 审核处理时间 */
    private LocalDateTime handledAt;
}
