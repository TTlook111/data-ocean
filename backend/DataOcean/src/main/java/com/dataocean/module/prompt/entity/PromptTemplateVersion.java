package com.dataocean.module.prompt.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Prompt 模板版本实体类
 * <p>
 * 记录每次模板变更的历史版本，支持版本回滚。
 * </p>
 */
@Data
@TableName("prompt_template_version")
public class PromptTemplateVersion {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的模板 ID */
    private Long templateId;

    /** 版本号 */
    private Integer versionNo;

    /** 该版本的模板内容 */
    private String content;

    /** 变更摘要说明 */
    private String changeSummary;

    /** 是否为当前活跃版本 */
    private Boolean isActive;

    /** 版本状态（DRAFT/PENDING_REVIEW/APPROVED/REJECTED） */
    private String status;

    /** 创建人 ID */
    private Long createdBy;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
