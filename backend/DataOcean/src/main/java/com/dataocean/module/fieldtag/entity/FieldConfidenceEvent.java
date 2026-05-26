package com.dataocean.module.fieldtag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 字段可信度变更事件实体类
 * <p>
 * 记录每次可信度变更的流水日志，支持审计追溯。
 * 事件类型包括：初始化、skills.md 定义、人工确认、管理员设置、
 * 查询成功、用户点赞、用户踩确认、群体阈值触发。
 * </p>
 */
@Data
@TableName("field_confidence_event")
public class FieldConfidenceEvent {

    /** 事件类型：Schema 初始化 */
    public static final String TYPE_SCHEMA_INIT = "SCHEMA_INIT";
    /** 事件类型：skills.md 中定义 */
    public static final String TYPE_SKILLS_MD_DEFINED = "SKILLS_MD_DEFINED";
    /** 事件类型：人工确认 */
    public static final String TYPE_MANUAL_CONFIRM = "MANUAL_CONFIRM";
    /** 事件类型：管理员手动设置 */
    public static final String TYPE_ADMIN_SET = "ADMIN_SET";
    /** 事件类型：查询成功使用 */
    public static final String TYPE_QUERY_SUCCESS = "QUERY_SUCCESS";
    /** 事件类型：用户点赞 */
    public static final String TYPE_USER_LIKE = "USER_LIKE";
    /** 事件类型：用户踩经审核确认 */
    public static final String TYPE_USER_DISLIKE_CONFIRMED = "USER_DISLIKE_CONFIRMED";
    /** 事件类型：群体阈值触发 */
    public static final String TYPE_GROUP_THRESHOLD = "GROUP_THRESHOLD";

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的字段元数据ID */
    private Long columnMetaId;

    /** 分数变化量（正数加分，负数扣分） */
    private Integer deltaScore;

    /** 事件类型 */
    private String eventType;

    /** 关联的查询任务ID（如有） */
    private Long sourceQueryId;

    /** 操作人用户ID */
    private Long operatorId;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
