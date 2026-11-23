package com.dataocean.module.feedback.entity;

import java.time.Instant;
import java.util.List;

/**
 * 原提问人自己的补充入口和进度，未关联原问不返回该对象。
 */
public record FeedbackSupplementVO(
        /**
         * 原事项的当前版本，用于保护回复提交。
         */
        int version,
        /**
         * 关联主事项当前公开进度。
         */
        String status,
        /**
         * 当前是否允许回复或主动补充。
         */
        boolean canSubmit,
        /**
         * 原问题个人可见的补充历史。
         */
        List<Entry> entries) {

    /**
     * 一次请求或主动说明，已过期或受限的正文可能为空。
     */
    public record Entry(
            /**
             * 补充记录稳定编号。
             */
            String id,
            /**
             * 正文归属的原事项，原提问端不提供后台事项编号。
             */
            String issueId,
            /**
             * 需要补充的具体业务信息。
             */
            String prompt,
            /**
             * 原提问人实际回复。
             */
            String answer,
            /**
             * 请求或主动说明提交时刻。
             */
            Instant requestedAt,
            /**
             * 已回复时刻，待回复时为空。
             */
            Instant answeredAt,
            /**
             * 人工处置类型，存在实际回复的补充记录为空。
             */
            String disposition,
            /**
             * 可见的处置依据，本人问数端始终省略。
             */
            String dispositionReason,
            /**
             * 负责人结束等待的时间，与实际回复时间分开。
             */
            Instant disposedAt) {}
}
