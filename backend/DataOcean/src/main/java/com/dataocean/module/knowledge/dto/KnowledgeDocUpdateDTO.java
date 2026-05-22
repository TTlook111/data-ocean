package com.dataocean.module.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 编辑知识文档请求参数
 */
@Data
public class KnowledgeDocUpdateDTO {

    /** 文档标题 */
    @NotBlank(message = "文档标题不能为空")
    private String title;

    /** 文档内容（Markdown） */
    private String content;

    /** 乐观锁版本号（前端传入当前版本） */
    @NotNull(message = "版本号不能为空")
    private Integer version;

    /** 变更摘要 */
    private String changeSummary;
}
