package com.dataocean.module.knowledge.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识文档实体类。
 * <p>
 * 对应 knowledge_doc 表，记录每个数据源关联的 skills.md 知识文档。
 * 文档经过审核发布后，其内容会被切片并向量化进入 RAG 系统。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("knowledge_doc")
public class KnowledgeDoc {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属数据源ID */
    private Long datasourceId;

    /** 文档标题 */
    private String title;

    /** 文档内容（Markdown 格式） */
    private String content;

    /** 当前版本号 */
    private Integer currentVersion;

    /** 文档状态（参见 DocStatus 枚举） */
    private String status;

    /** 审核状态（参见 ReviewStatus 枚举） */
    private String reviewStatus;

    /** 最后更新人用户ID */
    private Long updatedBy;

    /** 该文档覆盖的表名列表（JSON 数组） */
    private String tableNames;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 创建时间（自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间（自动填充） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 逻辑删除标记（0-未删除，1-已删除） */
    @TableLogic
    private Integer deleted;
}
