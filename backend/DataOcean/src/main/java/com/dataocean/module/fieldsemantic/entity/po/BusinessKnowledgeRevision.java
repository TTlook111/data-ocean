package com.dataocean.module.fieldsemantic.entity.po;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/** 平台内治理持久化记录。 */
@Data
@TableName("business_knowledge_revision")
public class BusinessKnowledgeRevision {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasourceId;
    private Long snapshotId;
    private String knowledgeKey;
    private String kind;
    private String title;
    private String bodyJson;
    private String dependenciesJson;
    private String sourceHash;
    private String sourceJson;
    private String contentHash;
    private String evidence;
    private String evidenceJson;
    private String unresolvedItemsJson;
    private String origin;
    private String status;
    private Long createdBy;
    private Long reviewedBy;
    private String reviewNote;
    private String verifiedHash;
    private Integer rowVersion;
    private java.time.LocalDateTime createdAt;
}
