package com.dataocean.module.query.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 查询提交请求 DTO
 */
@Data
public class QueryAskDTO {

    /** 数据源 ID */
    @NotNull(message = "数据源 ID 不能为空")
    private Long datasourceId;

    /** 用户自然语言问题 */
    @NotBlank(message = "问题不能为空")
    @Size(max = 500, message = "问题长度不能超过 500 字")
    private String question;

    /** 会话 ID（可选，不传则创建新会话） */
    private Long conversationId;

    /** 历史对话（最多 5 轮） */
    @Size(max = 5, message = "历史对话最多 5 轮")
    private List<Map<String, String>> conversationHistory;
}
