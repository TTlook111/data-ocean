package com.dataocean.module.knowledge.entity.vo;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前正式版本的暂停状态、恢复验证和原历史记录。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeVersionControlVO {

    /**
     * 当前范围尚未解除的暂停。
     */
    private Pause active;

    /**
     * 当前绑定的验证状态及下一步。
     */
    private Recovery recovery;

    /**
     * 暂停与显式解除的履历。
     */
    private List<Pause> history;

    /**
     * 当前正式绑定的暂停与解除记录。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Pause {

        /**
         * 暂停记录编号。
         */
        private String id;

        /**
         * 实际办理暂停的版本。
         */
        private String bindingId;

        /**
         * 实际暂停原因。
         */
        private String reason;

        /**
         * 办理暂停的人。
         */
        private String pausedBy;

        /**
         * 暂停提交时间。
         */
        private Instant pausedAt;

        /**
         * 暂停期间最近一轮验证的编号。
         */
        private String runId;

        /**
         * 该轮验证采用的绑定，用于恢复历史的确切入口。
         */
        private String runBindingId;

        /**
         * 恢复时填写的风险消除依据。
         */
        private String restoreReason;

        /**
         * 显式恢复的人。
         */
        private String restoredBy;

        /**
         * 显式解除时间。
         */
        private Instant restoredAt;
    }

    /**
     * 正式绑定当前可办理的恢复验证。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Recovery {

        /**
         * 当前恢复验证状态。
         */
        private String state;

        /**
         * 最近一轮运行编号。
         */
        private String runId;

        /**
         * 当前阻断原因或已完成证据的失效原因。
         */
        private String issue;

        /**
         * 当前绑定的依据允许发起新运行。
         */
        private boolean canRun;
    }

    /**
     * 原发布任务下本次隔离恢复验证及其证据。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RunDetail {

        /**
         * 正式绑定对应的原发布任务。
         */
        private String taskId;

        /**
         * 人工裁定采用的真实任务版本。
         */
        private int taskVersion;

        /**
         * 验证针对的确切暂停。
         */
        private String pauseId;

        /**
         * 版本上仍指向此轮，且验证依据仍匹配。
         */
        private boolean current;

        /**
         * 可阅读的逐题结果及历史。
         */
        private KnowledgeValidationVO validation;
    }
}
