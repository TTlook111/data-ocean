package com.dataocean.module.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** One immutable, explicitly confirmed datasource RAG build. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("rag_index_build")
public class RagIndexBuild {
    @TableId(type = IdType.INPUT)
    private String buildId;
    private Long datasourceId;
    private Long sourceSnapshotId;
    private String collectionName;
    private String embeddingProviderId;
    private String embeddingModel;
    private String embeddingBaseUrl;
    private Integer embeddingDimension;
    private String embeddingIndexVersion;
    private String embeddingFingerprint;
    private Long buildGeneration;
    private String manifestJson;
    private String status;
    private Integer expectedChunkCount;
    private Integer actualVectorCount;
    private Long confirmationUserId;
    private LocalDateTime confirmedAt;
    private LocalDateTime startedAt;
    private LocalDateTime verifiedAt;
    private LocalDateTime activatedAt;
    private LocalDateTime supersededAt;
    private LocalDateTime cleanupAfter;
    private LocalDateTime cleanupVerifiedAt;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
