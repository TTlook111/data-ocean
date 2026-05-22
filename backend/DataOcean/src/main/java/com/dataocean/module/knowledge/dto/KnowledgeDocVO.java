package com.dataocean.module.knowledge.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文档视图对象
 */
@Data
@Builder
public class KnowledgeDocVO {

    /** 文档 ID */
    private Long id;

    /** 关联数据源 ID */
    private Long datasourceId;

    /** 文档标题 */
    private String title;

    /** 文档内容 */
    private String content;

    /** 当前版本号 */
    private Integer currentVersion;

    /** 文档状态 */
    private String status;

    /** 审核状态 */
    private String reviewStatus;

    /** 乐观锁版本号 */
    private Integer version;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
