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
 * 知识切片实体类。
 * <p>
 * 对应 knowledge_chunk 表，记录知识文档拆分后的语义切片。
 * 每个切片对应一段独立的知识片段（表说明、关联路径、指标口径、字段防坑等），
 * 审核通过后向量化进入 Milvus 供 Schema RAG 召回使用。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_chunk")
public class KnowledgeChunk {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属文档ID */
    private Long docId;

    /** 所属文档版本号 */
    private Integer versionNo;

    /** 关联的元数据快照ID */
    private Long metadataSnapshotId;

    /** 切片类型（参见 ChunkType 枚举） */
    private String chunkType;

    /** 切片文本内容 */
    private String chunkText;

    /** 关联表名 */
    private String relatedTable;

    /** 关联字段名 */
    private String relatedColumn;

    /** 审核状态（参见 ReviewStatus 枚举） */
    private String reviewStatus;

    /** 向量化状态（参见 VectorTaskStatus 枚举） */
    private String vectorStatus;

    /** 创建时间（自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
