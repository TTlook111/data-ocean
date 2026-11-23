package com.dataocean.module.feedback.entity;

import java.util.List;

/**
 * 事项工作区中的原问题补充、主从关联及重开能力。
 */
public record FeedbackLifecycleVO(
        /**
         * 合并后的当前主事项，独立事项为空。
         */
        String mainIssueId,
        /**
         * 直接归并的原事项编号，均保留独立证据入口。
         */
        List<String> memberIssueIds,
        /**
         * 可联系的原问题集合，按当前证据权限过滤。
         */
        List<Recipient> supplementTargets,
        /**
         * 当前有权查看的必要补充及近期回复。
         */
        List<FeedbackSupplementVO.Entry> supplements,
        /**
         * 当前账号是未结主事项的负责人，可以处置待回复请求。
         */
        boolean canDisposeSupplement,
        /**
         * 当前事项是否可以作为重复项合并。
         */
        boolean canMerge,
        /**
         * 当前关联是否可凭依据纠正。
         */
        boolean canUnmerge,
        /**
         * 当前登录人是否可凭新证据重开。
         */
        boolean canReopen,
        /**
         * 当前事项完成轮次。
         */
        int completionRound,
        /**
         * 各轮次回告的实际独立投递结果。
         */
        List<NoticeRound> noticeRounds) {

    /**
     * 可联系的原问题及该问题的真实提问人。
     */
    public record Recipient(
            /**
             * 对应原事项编号。
             */
            String issueId,
            /**
             * 原问题唯一编号。
             */
            String requestId,
            /**
             * 当前可见的接收人名称。
             */
            String name,
            /**
             * 当前获准查看的原问文本。
             */
            String question) {}

    /**
     * 各轮次回告汇总，区分未投递、送达、受限、无法投递、已读。
     */
    public record NoticeRound(
            /**
             * 对应完成轮次。
             */
            int round,
            /**
             * 确认的接收人数。
             */
            int total,
            /**
             * 待投递数量。
             */
            int pending,
            /**
             * 送达的完整或受限回告数量。
             */
            int delivered,
            /**
             * 按受限模板送达的数量。
             */
            int restricted,
            /**
             * 当前账号无法接收的数量。
             */
            int suppressed,
            /**
             * 已读人数。
             */
            int readCount) {}
}
