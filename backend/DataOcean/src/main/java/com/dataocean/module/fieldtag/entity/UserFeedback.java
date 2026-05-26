package com.dataocean.module.fieldtag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户反馈实体类
 * <p>
 * 记录用户对查询结果中字段的反馈（点赞/点踩）。
 * 点赞直接触发可信度加分，点踩进入审核队列。
 * </p>
 */
@Data
@TableName("user_feedback")
public class UserFeedback {

    /** 反馈类型：点赞 */
    public static final String TYPE_LIKE = "LIKE";
    /** 反馈类型：点踩 */
    public static final String TYPE_DISLIKE = "DISLIKE";

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的查询任务ID */
    private Long queryTaskId;

    /** 关联的字段元数据ID */
    private Long columnMetaId;

    /** 反馈用户ID */
    private Long userId;

    /** 反馈类型：LIKE/DISLIKE */
    private String feedbackType;

    /** 原因编码（如 DATA_INACCURATE、FIELD_WRONG） */
    private String reasonCode;

    /** 用户补充说明 */
    private String comment;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
