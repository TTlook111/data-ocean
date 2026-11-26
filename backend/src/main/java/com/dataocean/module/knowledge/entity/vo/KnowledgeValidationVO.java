package com.dataocean.module.knowledge.entity.vo;

import com.dataocean.module.knowledge.service.KnowledgeBuildWire.Fragment;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 验证运行的当前状态、可以披露的实际证据和原始历史。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeValidationVO {

    /**
     * 当前回看的真实验证运行。
     */
    private Run run;

    /**
     * 先前各轮的实际运行状态和版本。
     */
    private List<Run> history;

    /**
     * 当前人员是否可阅读逐题实际结果。
     */
    private boolean canReadResults;

    /**
     * 当前人员是否可阅读来源、校验语句等验证依据。
     */
    private boolean canReadEvidence;

    /**
     * 本次运行逐题的实际结果及可见依据。
     */
    private List<Question> questions;

    /**
     * 每次独立运行关联确切候选与索引代次。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Run {

        /**
         * 可稳定定位当前请求或记录的编号。
         */
        private String id;

        /**
         * 本次验证对应的发布候选编号。
         */
        private String candidateId;

        /**
         * 本次验证所用的确切构建代次。
         */
        private String buildId;

        /**
         * 表示当前阶段的稳定状态编码。
         */
        private String state;

        /**
         * 实际接收或创建记录的 UTC 时刻。
         */
        private Instant createdAt;
    }

    /**
     * 审核预期与技术实际值分别回读，结论不覆盖原始证据。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Question {

        /**
         * 可稳定定位当前请求或记录的编号。
         */
        private String id;

        /**
         * 用于本轮验证的标准题编号。
         */
        private String testId;

        /**
         * 本次实际执行冻结的题目修订。
         */
        private String testRevisionId;

        /**
         * 所用证据、解释或知识文档的修订序号。
         */
        private int revision;

        /**
         * 已确认的验证或统计方式。
         */
        private String mode;

        /**
         * 当前轮次使用的业务问题。
         */
        private String question;

        /**
         * 本次复验预期的行为或结果。
         */
        private String expected;

        /**
         * 支撑事项核查或人工结论的依据。
         */
        private String basis;

        /**
         * 实际验证取得的数值，保留原始精度。
         */
        private String actualCount;

        /**
         * 有权查看的实际校验语句。
         */
        private String statement;

        /**
         * 无法完成验证时可披露的稳定分类。
         */
        private String failureCode;

        /**
         * 表示当前阶段的稳定状态编码。
         */
        private String state;

        /**
         * 执行此题所采用的当前可信主体编号。
         */
        private String executorId;

        /**
         * 按当前权限可见的正式知识证据。
         */
        private List<Fragment> evidence;

        /**
         * 说明题的当前人工裁定。
         */
        private Judgment judgment;
    }

    /**
     * 人工判断与说明题证据分别保存。理由可能包含原文，仅向有权读正文的人员披露。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Judgment {

        /**
         * 本次判断采用的受控结论。
         */
        private String decision;

        /**
         * 此动作采用的实际业务理由。
         */
        private String reason;

        /**
         * 执行人工裁定的人员编号。
         */
        private String decidedBy;

        /**
         * 人工裁定落盘的 UTC 时刻。
         */
        private Instant decidedAt;
    }
}
