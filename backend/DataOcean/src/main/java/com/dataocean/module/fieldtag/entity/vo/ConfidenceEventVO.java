package com.dataocean.module.fieldtag.entity.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 可信度变更事件视图对象
 * <p>
 * 返回给前端的可信度变更历史记录。
 * </p>
 */
@Data
public class ConfidenceEventVO {

    /** 事件ID */
    private Long id;

    /** 分数变化量 */
    private Integer deltaScore;

    /** 事件类型 */
    private String eventType;

    /** 关联的查询任务ID */
    private Long sourceQueryId;

    /** 操作人用户ID */
    private Long operatorId;

    /** 操作人用户名（冗余展示） */
    private String operatorName;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
