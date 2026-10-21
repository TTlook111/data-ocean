package com.dataocean.module.glossary.entity.po;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 不可变审核记录，快照包含业务定义和完整字段映射。 */
@Data
@TableName("glossary_term_review")
public class GlossaryTermReview {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long termId;
    private Long revision;
    private String action;
    private String contentHash;
    private String snapshotJson;
    private String reason;
    private Long actorId;
    private LocalDateTime createdAt;
}
