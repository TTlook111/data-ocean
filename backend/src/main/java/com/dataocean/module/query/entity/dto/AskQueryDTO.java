package com.dataocean.module.query.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一轮请求的固定输入与原轮次关系。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AskQueryDTO {

    /**
     * 本轮问数使用的数据源编号。
     */
    private String sourceId;

    /**
     * 当前轮次使用的业务问题。
     */
    private String question;

    /**
     * 有明确前文时使用的同源原轮次编号。
     */
    private String previousId;

    /**
     * 本轮归属的同一对话。
     */
    private String conversationId;

    /**
     * 是否允许复用已经核对的同源对话依据。
     */
    private Boolean inheritContext;
}
