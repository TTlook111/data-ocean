package com.dataocean.module.feedback.entity.vo;

import com.dataocean.module.feedback.entity.vo.FeedbackIssueDetailVO.Resolution;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 事项内任务的责任与当前办理能力，正文沿用原问题证据权限。
 */
@Data
@AllArgsConstructor
public class FeedbackTaskVO {

    /**
     * 事项内稳定任务编号。
     */
    private int id;

    /**
     * 治理、技术或源数据修正路径。
     */
    private String direction;

    /**
     * 当前任务负责人的业务编号。
     */
    private String assigneeId;

    /**
     * 当前任务负责人的显示名称。
     */
    private String assigneeName;

    /**
     * 由分派人确认的任务范围，受限时为空。
     */
    private String scope;

    /**
     * 在办、已提交、无需变更或已取消。
     */
    private String status;

    /**
     * 是否可为当前任务记录核查。
     */
    private boolean canReview;

    /**
     * 是否可转派该任务。
     */
    private boolean canAssign;

    /**
     * 是否可办理退出或恢复。
     */
    private boolean canChangeStatus;

    /**
     * 技术修复尚未生效或源系统仍在修正、同步，任务继续在办。
     */
    private boolean waitingExternal;

    /**
     * 本轮仍有效的进展，已提交结果或重新分派后为空。
     */
    private Progress progress;

    /**
     * 此任务的处理结果、复验和可用动作。
     */
    private Resolution resolution;

    /**
     * 当前任务最近一次仍有效的跟进正文，沿用原问证据可见范围。
     */
    @Data
    @AllArgsConstructor
    public static class Progress {

        /**
         * 已完成的排查、沟通或核对工作，正文受限时为空。
         */
        private String workDone;

        /**
         * 继续跟进的安排，正文受限时为空。
         */
        private String nextStep;
    }
}
