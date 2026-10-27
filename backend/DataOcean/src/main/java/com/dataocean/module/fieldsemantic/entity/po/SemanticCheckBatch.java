package com.dataocean.module.fieldsemantic.entity.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Independent semantic review batch; never feeds SOURCE scoring or release gates. */
@Data
@TableName("semantic_check_batch")
public class SemanticCheckBatch {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasourceId;
    private Long snapshotId;
    private String status;
    private Integer applicableCount;
    private Integer validCount;
    private Integer missingCount;
    private Integer staleCount;
    private Integer notApplicableCount;
    private String errorSummary;
    private Integer ruleVersion;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long createdBy;
}
