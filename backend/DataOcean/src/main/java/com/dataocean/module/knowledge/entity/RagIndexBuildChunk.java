package com.dataocean.module.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Frozen, fact-level fallback membership for one build. */
@Data
@TableName("rag_index_build_chunk")
public class RagIndexBuildChunk {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String buildId;
    private Long chunkId;
    private Long datasourceId;
    private Long sourceSnapshotId;
    private Long docId;
    private Integer versionNo;
    private String resourceDependencies;
    private String factSourceIds;
    private String factType;
    private String factReviewStatus;
    private String governanceStatus;
    private LocalDateTime createdAt;
}
