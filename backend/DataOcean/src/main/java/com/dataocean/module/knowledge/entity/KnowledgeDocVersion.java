package com.dataocean.module.knowledge.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识文档版本实体类。
 * <p>
 * 对应 knowledge_doc_version 表，记录知识文档的每次变更版本。
 * 每次编辑或 AI 生成都会产生新版本，支持版本回滚。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_doc_version")
public class KnowledgeDocVersion {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属文档ID */
    private Long docId;

    /** 所属数据源ID */
    private Long datasourceId;

    /** 关联的元数据快照ID */
    private Long metadataSnapshotId;

    /** 版本号 */
    private Integer versionNo;

    /** 版本内容（Markdown 格式） */
    private String content;

    /** 生成来源（参见 GenerationSource 枚举） */
    private String generationSource;

    /** 审核状态（参见 ReviewStatus 枚举） */
    private String reviewStatus;

    /** 审核人用户ID */
    private Long reviewerId;

    /** 变更摘要 */
    private String changeSummary;

    /** 创建人用户ID */
    private Long createdBy;

    /** 创建时间（自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
