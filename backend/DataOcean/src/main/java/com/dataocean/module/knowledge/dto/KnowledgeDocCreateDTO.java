package com.dataocean.module.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建知识文档请求参数
 */
@Data
public class KnowledgeDocCreateDTO {

    /** 关联数据源 ID */
    @NotNull(message = "数据源 ID 不能为空")
    private Long datasourceId;

    /** 文档标题 */
    @NotBlank(message = "文档标题不能为空")
    private String title;

    /** 文档内容（Markdown） */
    private String content;
}
