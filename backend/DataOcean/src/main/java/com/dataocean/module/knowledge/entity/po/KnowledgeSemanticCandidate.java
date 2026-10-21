package com.dataocean.module.knowledge.entity.po;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("knowledge_semantic_candidate")
public class KnowledgeSemanticCandidate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long docId;
    private Long datasourceId;
    private Long snapshotId;
    private Integer baseVersion;
    private String candidateText;
    private String dependenciesJson;
    private String inputJson;
    private String confirmedText;
    private Long confirmedBy;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;
}
