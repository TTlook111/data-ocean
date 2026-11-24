package com.dataocean.module.knowledge.entity.vo;

import java.util.List;

/**
 * 标准题建议的运行状态与可采纳结果。
 *
 * @param id 生成任务编号
 * @param documentId 关联文档编号
 * @param documentRevisionId 生成采用的确切确认版本
 * @param state 任务真实运行状态
 * @param failureCode 可披露失败原因
 * @param candidates 按本轮顺序可回看的候选题
 */
public record KnowledgeTestSuggestionVO(
        String id, String documentId, String documentRevisionId, String state, String failureCode,
        List<Candidate> candidates) {

    /**
     * 题目候选和确切文档章节的定位。
     *
     * @param question 建议的题干
     * @param coverage 关键能力或使用边界
     * @param mode 数值或说明型判定
     * @param validationHint 人工核对判定点的提示
     * @param sectionTitles 引用的确认章节标题
     * @param testId 明确采纳后保存的题目编号
     */
    public record Candidate(String question, String coverage, String mode, String validationHint,
            List<String> sectionTitles, String testId) {}
}
