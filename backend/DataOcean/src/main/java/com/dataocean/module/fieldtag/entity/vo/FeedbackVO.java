package com.dataocean.module.fieldtag.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户反馈视图对象
 * <p>
 * 返回给前端的反馈信息，包含审核状态。
 * </p>
 */
@Data
public class FeedbackVO {

    /** 反馈ID */
    private Long id;

    /** 查询任务ID */
    private Long queryTaskId;

    /** 字段元数据ID */
    private Long columnMetaId;

    /** 字段名称（冗余展示） */
    private String columnName;

    /** 表名（冗余展示） */
    private String tableName;

    /** 反馈用户ID */
    private Long userId;

    /** 反馈用户名（冗余展示） */
    private String username;

    /** 反馈类型：LIKE/DISLIKE */
    private String feedbackType;

    /** 原因编码 */
    private String reasonCode;

    /** 用户补充说明 */
    private String comment;

    /** 审核状态（DISLIKE 类型才有） */
    private String reviewStatus;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
