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
 * 向量化索引任务实体类。
 * <p>
 * 对应 vector_index_task 表，记录知识切片向量化到 Milvus 的任务状态。
 * Java 创建任务后调用 Python 服务执行向量化，Python 回调更新任务状态。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("vector_index_task")
public class VectorIndexTask {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属数据源ID */
    private Long datasourceId;

    /** 目标类型（如 CHUNK、DOC） */
    private String targetType;

    /** 目标对象ID */
    private Long targetId;

    /** 关联的元数据快照ID */
    private Long metadataSnapshotId;

    /** 待写入 RAG 的知识文档版本号 */
    private Integer knowledgeVersionNo;

    /** 新版本写入成功后需要清理的上一版知识文档版本号 */
    private Integer previousVersionNo;

    /** 任务状态（参见 VectorTaskStatus 枚举） */
    private String status;

    /** 任务开始时间 */
    private LocalDateTime startedAt;

    /** 任务完成时间 */
    private LocalDateTime finishedAt;

    /** 错误信息（失败时记录） */
    private String errorMessage;

    /** 创建时间（自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
