package com.dataocean.module.prompt.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Prompt 模板实体类
 * <p>
 * 存储 Prompt 模板的基本信息和当前活跃版本内容。
 * 使用乐观锁防止并发编辑冲突。
 * 支持审批流程：DRAFT → PENDING_REVIEW → APPROVED / REJECTED
 * </p>
 */
@Data
@TableName("prompt_template")
public class PromptTemplate {

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模板唯一编码，用于 Python 服务按 code 获取模板 */
    private String templateCode;

    /** 模板名称 */
    private String templateName;

    /** 使用场景（如 query_rewrite、sql_generate 等） */
    private String scenario;

    /** 当前活跃版本的模板内容 */
    private String content;

    /** 当前版本号 */
    private Integer currentVersion;

    /** 模板状态（DRAFT/PENDING_REVIEW/APPROVED/REJECTED） */
    private String status;

    /** 是否启用线上已发布版本 */
    private Boolean enabled;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
