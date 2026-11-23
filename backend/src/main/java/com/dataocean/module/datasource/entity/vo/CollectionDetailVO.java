package com.dataocean.module.datasource.entity.vo;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务详情仅呈现实际状态允许披露的内容。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CollectionDetailVO {

    /**
     * 任务编号。
     */
    private String taskId;

    /**
     * 展示编号。
     */
    private String taskNo;

    /**
     * 归属源。
     */
    private String sourceId;

    /**
     * 发起方式。
     */
    private String triggerKind;

    /**
     * 发起主体。
     */
    private Actor actor;

    /**
     * 用户可见的本次采集范围。
     */
    private String scopeLabel;

    /**
     * 固定使用的数据库与 Schema。
     */
    private Scope scope;

    /**
     * 固定的只读账号。
     */
    private String account;

    /**
     * 接收时间。
     */
    private Instant createdAt;

    /**
     * 真实开始时间。
     */
    private Instant startedAt;

    /**
     * 真实结束时间。
     */
    private Instant endedAt;

    /**
     * 人工重试对应原任务。
     */
    private String parentTaskId;

    /**
     * 固定的连接修订。
     */
    private int configRevision;

    /**
     * 当前五态。
     */
    private String state;

    /**
     * 源侧执行事实。
     */
    private String executionState;

    /**
     * 当前状态版本。
     */
    private int version;

    /**
     * 执行事件的代次标识。
     */
    private String streamEpoch;

    /**
     * 取消及停止进度。
     */
    private StopProgress stopProgress;

    /**
     * 当前状态允许显示的内容。
     */
    private Content content;

    /**
     * 任务事件中的安全主体引用。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Actor {

        /**
         * USER 或 SYSTEM。
         */
        private String kind;

        /**
         * 用户编号，系统为空。
         */
        private String id;

        /**
         * 登录名，系统为空。
         */
        private String loginName;

        /**
         * 展示姓名或固定系统名称。
         */
        private String name;
    }

    /**
     * 共用任务和目录的只读范围。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Scope {

        /**
         * 数据库类型。
         */
        private String databaseType;

        /**
         * 目标库名。
         */
        private String databaseName;

        /**
         * 此修订的 PostgreSQL 范围。
         */
        private List<String> schemas;
    }

    /**
     * 当前可显示的停止观察。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StopProgress {

        /**
         * 接收取消意图的时间。
         */
        private Instant cancelRequestedAt;

        /**
         * 确认所有源执行结束的时间。
         */
        private Instant stopConfirmedAt;

        /**
         * 独立状态。
         */
        private String executionState;

        /**
         * 可披露原因。
         */
        private String message;
    }

    /**
     * 状态内容由后端裁定，失败不带候选计数。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Content {

        /**
         * 当前读取阶段。
         */
        private String phase;

        /**
         * 运行中进度。
         */
        private Counts counts;

        /**
         * 成功快照。
         */
        private String snapshotId;

        /**
         * 上一次成功快照。
         */
        private String previousSuccessSnapshotId;

        /**
         * 实际比较快照。
         */
        private String comparisonSnapshotId;

        /**
         * 成功读取对象数。
         */
        private Long collectedObjectCount;

        /**
         * 成功分析结果。
         */
        private String analysis;

        /**
         * 发现的变化数量。
         */
        private Long changeCount;

        /**
         * 稳定失败原因代码。
         */
        private String failureCode;

        /**
         * 可披露失败原因。
         */
        private String failureMessage;

        /**
         * 取消原因。
         */
        private String reason;
    }

    /**
     * 客户端可采用的明确采集计数。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Counts {

        /**
         * 对象枚举是否完整。
         */
        private boolean enumerationComplete;

        /**
         * 已发现数。
         */
        private long discoveredCount;

        /**
         * 完整枚举前未知。
         */
        private Long totalCount;

        /**
         * 已尝试读取数。
         */
        private long attemptedCount;

        /**
         * 完整结构数。
         */
        private long completeCount;

        /**
         * 部分结构数。
         */
        private long partialCount;

        /**
         * 读取失败数。
         */
        private long failedCount;

        /**
         * 当前尚未处理数。
         */
        private long unprocessedCount;
    }
}
