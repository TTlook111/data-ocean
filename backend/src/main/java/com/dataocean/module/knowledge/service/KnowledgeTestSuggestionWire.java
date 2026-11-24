package com.dataocean.module.knowledge.service;

import com.dataocean.module.knowledge.entity.vo.KnowledgeDocumentVO.Content;
import com.dataocean.module.model.service.ModelInvocation;

import java.util.List;
import java.util.Map;

/**
 * 标准题建议的确切输入、受控回包及本地采纳记录。
 */
final class KnowledgeTestSuggestionWire {

    /**
     * 只声明内部接口需要的记录类型。
     */
    private KnowledgeTestSuggestionWire() {}

    /**
     * @param configuration 发起时的数据源连接修订
     * @param input 可发送给模型的已确认资料
     */
    record Snapshot(Long configuration, Input input) {}

    /**
     * @param document 同源确切确认正文
     * @param existingQuestions 用于减少重复的近期题干
     */
    record Input(Content document, List<String> existingQuestions) {}

    /**
     * @param taskId 回执使用的实际任务编号
     * @param model 节点冻结的模型配置
     * @param input 生成依据与题干
     */
    record Request(String taskId, ModelInvocation model, Input input) {}

    /**
     * @param candidates 模型提出的题目建议
     */
    record Response(List<Suggestion> candidates) {}

    /**
     * @param question 可供人工调整的题干
     * @param coverage 关键能力或边界覆盖
     * @param mode 当前题单可办理的判定方式
     * @param validationHint 需要人工核对的判定要点
     * @param sections 原确认正文中实际存在的章节序号，从零开始
     */
    record Suggestion(String question, String coverage, String mode, String validationHint, List<Integer> sections) {}

    /**
     * @param candidates 经过后端校验并去重的实际建议
     * @param adoptions 人工采纳索引到已保存的题目编号
     */
    record Report(List<Suggestion> candidates, Map<Integer, String> adoptions) {}
}
