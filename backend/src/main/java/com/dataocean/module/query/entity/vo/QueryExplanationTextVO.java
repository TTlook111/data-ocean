package com.dataocean.module.query.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型生成的解释须再经过 Java 的事实核对与输出门禁。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryExplanationTextVO {

    /**
     * 经过现行规则允许返回的文字内容。
     */
    private String text;
}
