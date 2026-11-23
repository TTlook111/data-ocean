package com.dataocean.module.query.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 展示中使用解释的确切修订，失败不改变查询结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryExplanationVO {

    /**
     * 所用证据、解释或知识文档的修订序号。
     */
    private int revision;

    /**
     * 当前解释的实际生成状态。
     */
    private String status;

    /**
     * 经过现行规则允许返回的文字内容。
     */
    private String text;
}
