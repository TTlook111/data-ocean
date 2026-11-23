package com.dataocean.module.feedback.entity.vo;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 原提问人自己的补充入口和进度，未关联原问不返回该对象。
 */
@Data
@AllArgsConstructor
public class FeedbackSupplementVO {

    /**
     * 原事项的当前版本，用于保护回复提交。
     */
    private int version;

    /**
     * 关联主事项当前公开进度。
     */
    private String status;

    /**
     * 当前是否允许回复或主动补充。
     */
    private boolean canSubmit;

    /**
     * 原问题个人可见的补充历史。
     */
    private List<Entry> entries;

    /**
     * 一次请求或主动说明，已过期或受限的正文可能为空。
     */
    @Data
    @AllArgsConstructor
    public static class Entry {

        /**
         * 补充记录稳定编号。
         */
        private String id;

        /**
         * 正文归属的原事项，原提问端不提供后台事项编号。
         */
        private String issueId;

        /**
         * 需要补充的具体业务信息。
         */
        private String prompt;

        /**
         * 原提问人实际回复。
         */
        private String answer;

        /**
         * 请求或主动说明提交时刻。
         */
        private Instant requestedAt;

        /**
         * 已回复时刻，待回复时为空。
         */
        private Instant answeredAt;

        /**
         * 人工处置类型，存在实际回复的补充记录为空。
         */
        private String disposition;

        /**
         * 可见的处置依据，本人问数端始终省略。
         */
        private String dispositionReason;

        /**
         * 负责人结束等待的时间，与实际回复时间分开。
         */
        private Instant disposedAt;
    }
}
