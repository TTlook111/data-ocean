package com.dataocean.module.knowledge.entity.vo;

import com.dataocean.module.knowledge.entity.dto.KnowledgeTestDraftDTO.CountRule;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 实际题目草稿、审查状态和确切验证条件。
 */
@Data
@AllArgsConstructor
public class KnowledgeTestVO {

    /**
     * 列表和详情共用当前题目状态。
     */
    private Row test;

    /**
     * 实际保存的题目内容和期望。
     */
    private Content content;

    /**
     * 所属源内可用于准备标准题的文档确认。
     */
    @Data
    @AllArgsConstructor
    public static class DocumentOption {

        /**
         * 文档稳定编号。
         */
        private String id;

        /**
         * 确认正文的标题。
         */
        private String title;

        /**
         * 实际可用的确认修订。
         */
        private String revisionId;
    }

    /**
     * 数值题所用的固定事实。
     */
    @Data
    @AllArgsConstructor
    public static class FactOption {

        /**
         * 稳定治理身份，使用实际类型解释。
         */
        private String id;

        /**
         * 所属治理类别。
         */
        private String kind;

        /**
         * 用于题单条件选择的名称。
         */
        private String label;
    }

    /**
     * 记录确认来源的题单摘要。
     */
    @Data
    @AllArgsConstructor
    public static class Row {

        /**
         * 题目稳定身份。
         */
        private String id;

        /**
         * 所属文档。
         */
        private String documentId;

        /**
         * 当前题干。
         */
        private String question;

        /**
         * 当前写操作的并发版本。
         */
        private int version;

        /**
         * 当前可供审核的确切修订。
         */
        private String revisionId;

        /**
         * 当前正文已经审核、待审或依据已变化。
         */
        private String state;
    }

    /**
     * 只追加的预期与判定条件。
     */
    @Data
    @AllArgsConstructor
    public static class Content {

        /**
         * 模式和覆盖点。
         */
        private String mode;

        /**
         * 关键能力或边界。
         */
        private String coverage;

        /**
         * 已固定的题目和代表性证据。
         */
        private String question;

        /**
         * 数值期望。
         */
        private Long expectedCount;

        /**
         * 人工判断的预期要点。
         */
        private String expectedExplanation;

        /**
         * 预期所依据的独立样例。
         */
        private String evidence;

        /**
         * 固定的结构化查询参数。
         */
        private CountRule countRule;

        /**
         * 准备时引用的实际文档确认。
         */
        private String documentRevisionId;
    }
}
