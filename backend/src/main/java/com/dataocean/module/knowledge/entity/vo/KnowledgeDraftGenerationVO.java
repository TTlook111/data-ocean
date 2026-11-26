package com.dataocean.module.knowledge.entity.vo;

import java.util.List;

/**
 * 作者回看的正文任务和逐节差异。
 *
 * @param id 实际生成任务编号
 * @param suggestionIndex 原规划建议序号
 * @param state 当前排队、运行或终态
 * @param failureCode 可披露失败分类
 * @param existingDocumentId 本次核对更新的原文档
 * @param appliedDocumentId 已明确采纳的草稿文档
 * @param changes 待人工选择的生成章节
 */
public record KnowledgeDraftGenerationVO(
        String id,
        int suggestionIndex,
        String state,
        String failureCode,
        String existingDocumentId,
        String appliedDocumentId,
        List<Change> changes) {

    /**
     * 依据共享知识章节模型展示原文和待采纳建议。
     *
     * @param original 待替换的原章节，新增时为空
     * @param proposed 待采纳的实际正文
     * @param basis 该章节使用的确切治理定义和修订
     */
    public record Change(
            KnowledgeDocumentVO.Section original,
            KnowledgeDocumentVO.Section proposed,
            List<KnowledgePlanVO.FactReference> basis) {}
}
