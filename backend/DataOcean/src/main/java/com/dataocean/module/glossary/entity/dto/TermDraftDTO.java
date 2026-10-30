package com.dataocean.module.glossary.entity.dto;

import lombok.Data;
import java.util.List;

/** 术语定义和字段映射的完整保存请求。 */
@Data
public class TermDraftDTO {
    private Long revision;
    private String name;
    private String displayName;
    private String description;
    private String synonyms;
    private String relatedTerms;
    private List<Long> entityIds = List.of();
    private boolean submit;
}
