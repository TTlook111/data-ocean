package com.dataocean.module.knowledge.entity.vo;

import java.util.List;

/**
 * 当前发起人可回看的规划状态及建议摘要。
 *
 * @param id 规划任务编号
 * @param state 实际执行状态
 * @param failureCode 失败时的受控原因
 * @param documents 生成的文档建议
 * @param facts 建议引用的同源治理快照标识
 * @param existingDocuments 可回看和定位的同源现有文档
 */
public record KnowledgePlanVO(
        String id,
        String state,
        String failureCode,
        List<Suggestion> documents,
        List<FactReference> facts,
        List<DocumentReference> existingDocuments) {

    /**
     * 模型提出的单篇规划，由用户核对后才能进入正文生成。
     *
     * @param title 建议名称
     * @param purpose 文档职责与适用条件
     * @param questions 代表性问题
     * @param exclusions 不适用范围
     * @param gaps 当前欠缺的治理依据
     * @param basis 引用本次快照的事实序号
     * @param existingDocumentId 可定位的已有文档，新增建议为空
     */
    public record Suggestion(
            String title,
            String purpose,
            List<String> questions,
            List<String> exclusions,
            List<String> gaps,
            List<Integer> basis,
            String existingDocumentId) {}

    /**
     * 用于回看规划依据，正文和模型输入不从任务详情返回。
     *
     * @param number 快照内序号
     * @param label 已核验的治理名称
     * @param objectId 源内对象编号
     * @param kind 治理类别
     * @param targetId 稳定条目编号
     * @param revisionId 当时采用的确认修订
     */
    public record FactReference(
            int number, String label, String objectId, String kind, String targetId, String revisionId) {}

    /**
     * 规划时用来定位已有文档的同源条目。
     *
     * @param id 现有文档编号
     * @param title 本轮规划所见文档名
     */
    public record DocumentReference(String id, String title) {}
}
