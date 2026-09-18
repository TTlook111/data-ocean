package com.dataocean.module.permission.s1.entity.vo;

/**
 * 结构化记录条件的中文摘要。只用于管理端授权配置展示，不进入模型上下文、SQL 解释或审计详情。
 *
 * @param valueSummary 条件值的中文摘要，例如“等于 华东”
 */
public record IamS1RowConditionSummaryVO(String columnName,
                                         String operatorCode,
                                         String operatorName,
                                         String valueSummary) {
}
