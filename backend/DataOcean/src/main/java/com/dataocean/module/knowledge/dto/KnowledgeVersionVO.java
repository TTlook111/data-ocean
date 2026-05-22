package com.dataocean.module.knowledge.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文档版本视图对象
 */
@Data
@Builder
public class KnowledgeVersionVO {

    /** 版本 ID */
    private Long id;

    /** 文档 ID */
    private Long docId;

    /** 版本号 */
    private Integer versionNo;

    /** 版本内容 */
    private String content;

    /** 生成来源 */
    private String generationSource;

    /** 审核状态 */
    private String reviewStatus;

    /** 关联快照 ID */
    private Long metadataSnapshotId;

    /** 变更摘要 */
    private String changeSummary;

    /** 创建人 ID */
    private Long createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
