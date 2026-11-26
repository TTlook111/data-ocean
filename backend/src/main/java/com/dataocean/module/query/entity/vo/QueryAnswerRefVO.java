package com.dataocean.module.query.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 已交付结果的评价身份，与解释和执行代次独立。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryAnswerRefVO {

    /**
     * 允许核对的原问或已交付回答编号。
     */
    private String answerId;

    /**
     * 稳定的回答修订号，与解释修订独立。
     */
    private int answerRevision;
}
