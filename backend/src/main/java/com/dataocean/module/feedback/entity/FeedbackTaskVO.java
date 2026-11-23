package com.dataocean.module.feedback.entity;

import com.dataocean.module.feedback.entity.FeedbackIssueDetailVO.Resolution;

/**
 * 事项内任务的责任与当前办理能力，正文沿用原问题证据权限。
 */
public record FeedbackTaskVO(
        /**
         * 事项内稳定任务编号。
         */
        int id,
        /**
         * 治理、技术或源数据修正路径。
         */
        String direction,
        /**
         * 当前任务负责人的业务编号。
         */
        String assigneeId,
        /**
         * 当前任务负责人的显示名称。
         */
        String assigneeName,
        /**
         * 由分派人确认的任务范围，受限时为空。
         */
        String scope,
        /**
         * 在办、已提交、无需变更或已取消。
         */
        String status,
        /**
         * 是否可为当前任务记录核查。
         */
        boolean canReview,
        /**
         * 是否可转派该任务。
         */
        boolean canAssign,
        /**
         * 是否可办理退出或恢复。
         */
        boolean canChangeStatus,
        /**
         * 技术修复尚未生效或源系统仍在修正、同步，任务继续在办。
         */
        boolean waitingExternal,
        /**
         * 此任务的处理结果、复验和可用动作。
         */
        Resolution resolution) {}
