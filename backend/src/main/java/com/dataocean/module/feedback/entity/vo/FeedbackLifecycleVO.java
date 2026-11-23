package com.dataocean.module.feedback.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 事项工作区中的原问题补充、主从关联及重开能力。
 */
@Data
@AllArgsConstructor
public class FeedbackLifecycleVO {

    /**
     * 合并后的当前主事项，独立事项为空。
     */
    private String mainIssueId;

    /**
     * 直接归并的原事项编号，均保留独立证据入口。
     */
    private List<String> memberIssueIds;

    /**
     * 可联系的原问题集合，按当前证据权限过滤。
     */
    private List<Recipient> supplementTargets;

    /**
     * 当前有权查看的必要补充及近期回复。
     */
    private List<FeedbackSupplementVO.Entry> supplements;

    /**
     * 当前账号是未结主事项的负责人，可以处置待回复请求。
     */
    private boolean canDisposeSupplement;

    /**
     * 当前事项是否可以作为重复项合并。
     */
    private boolean canMerge;

    /**
     * 当前关联是否可凭依据纠正。
     */
    private boolean canUnmerge;

    /**
     * 当前登录人是否可凭新证据重开。
     */
    private boolean canReopen;

    /**
     * 当前事项完成轮次。
     */
    private int completionRound;

    /**
     * 各轮次回告的实际独立投递结果。
     */
    private List<NoticeRound> noticeRounds;

    /**
     * 可联系的原问题及该问题的真实提问人。
     */
    @Data
    @AllArgsConstructor
    public static class Recipient {

        /**
         * 对应原事项编号。
         */
        private String issueId;

        /**
         * 原问题唯一编号。
         */
        private String requestId;

        /**
         * 当前可见的接收人名称。
         */
        private String name;

        /**
         * 当前获准查看的原问文本。
         */
        private String question;
    }

    /**
     * 各轮次回告汇总，区分未投递、送达、受限、无法投递、已读。
     */
    @Data
    @AllArgsConstructor
    public static class NoticeRound {

        /**
         * 对应完成轮次。
         */
        private int round;

        /**
         * 确认的接收人数。
         */
        private int total;

        /**
         * 待投递数量。
         */
        private int pending;

        /**
         * 送达的完整或受限回告数量。
         */
        private int delivered;

        /**
         * 按受限模板送达的数量。
         */
        private int restricted;

        /**
         * 当前账号无法接收的数量。
         */
        private int suppressed;

        /**
         * 已读人数。
         */
        private int readCount;
    }
}
