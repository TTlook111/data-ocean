package com.dataocean.module.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** The only query-visible RAG pointer for one datasource. */
@Data
@TableName("rag_index_state")
public class RagIndexState {
    @TableId(type = IdType.INPUT)
    private Long datasourceId;
    private String activeBuildId;
    private String latestBuildId;
    private Long nextGeneration;
    private LocalDateTime updatedAt;
}
