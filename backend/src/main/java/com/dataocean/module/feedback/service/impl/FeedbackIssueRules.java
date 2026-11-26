package com.dataocean.module.feedback.service.impl;

import com.dataocean.common.exception.BizIllegalException;
import com.dataocean.module.feedback.mapper.FeedbackMapper.Issue;
import com.dataocean.module.feedback.mapper.FeedbackTaskMapper.Task;

import java.util.Objects;
import java.util.UUID;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 事项、任务与办理输入共用的状态规则。
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class FeedbackIssueRules {

    /**
     * 分派权限和原分派命令的回查标识。
     */
    static final String ASSIGN = "feedback.assign";

    /**
     * 填写核查、处理结果及跟进的权限。
     */
    static final String PROCESS = "feedback.process";

    /**
     * 登记复验及发起原问复验的权限。
     */
    static final String VERIFY = "feedback.verify";

    /**
     * 核对接收人并完成结案的权限。
     */
    static final String CLOSE = "feedback.close";

    /**
     * 查看反馈队列和事项基础事实的权限。
     */
    static final String VIEW = "feedback.view";

    /**
     * 事项尚未确定主负责人。
     */
    static final String PENDING = "PENDING_ASSIGNMENT";

    /**
     * 事项或任务正在办理。
     */
    static final String IN_PROGRESS = "IN_PROGRESS";

    /**
     * 已提交处理结果，等待登记或确认复验。
     */
    static final String TO_VERIFY = "PENDING_VERIFICATION";

    /**
     * 当前有效复验支持按已解决结案。
     */
    static final String RESOLVED = "RESOLVED";

    /**
     * 按核查事实说明结案。
     */
    static final String CLOSED_OTHER = "CLOSED_OTHER";

    /**
     * 必要用户补充尚未齐备时的展示状态。
     */
    static final String WAITING = "PENDING_SUPPLEMENT";

    /**
     * 重复事项保留原证据并随主事项办理。
     */
    static final String MERGED = "MERGED";

    /**
     * 平台记录源系统修正并开展只读复验。
     */
    static final String SOURCE_DATA = "SOURCE_DATA";

    /**
     * 独立任务已提交当前处理结果。
     */
    static final String SUBMITTED = "SUBMITTED";

    /**
     * 单技术事项在办理或待验证时都允许当前负责人修订。
     */
    static boolean ownsOpenWork(Issue row, long actor) {
        return (IN_PROGRESS.equals(row.status()) || TO_VERIFY.equals(row.status()))
                && Objects.equals(row.assigneeId(), actor);
    }

    /**
     * 在办和已提交的任务都属于此次办理范围。
     */
    static boolean active(Task task) {
        return IN_PROGRESS.equals(task.status()) || SUBMITTED.equals(task.status());
    }

    /**
     * 只有当前任务负责人可以在事项办理期间保存结果修订。
     */
    static boolean canWork(Issue row, Task task, long actor) {
        return (IN_PROGRESS.equals(row.status()) || TO_VERIFY.equals(row.status()))
                && active(task)
                && actor == task.assigneeId();
    }

    /**
     * 可继续办理的事项包括新线索以及现有处理中事项。
     */
    static boolean assignable(Issue row) {
        return PENDING.equals(row.status()) || IN_PROGRESS.equals(row.status()) || TO_VERIFY.equals(row.status());
    }

    /**
     * 权限与当前主负责人事实同时核对。
     */
    static boolean owner(Issue row, long actor) {
        return IN_PROGRESS.equals(row.status()) && Objects.equals(row.assigneeId(), actor);
    }

    /**
     * 无效编号不回显存储信息。
     */
    static Issue requireIssue(Issue row) {
        if (row == null) {
            // 缺失事项按统一资源错误返回。
            throw BizIllegalException.missing();
        }
        return row;
    }

    /**
     * 等待用户补充始终优先于任务的当前办理阶段。
     */
    static String displayStatus(Issue row) {
        return row.pendingSupplement() ? WAITING : row.status();
    }

    /**
     * 避免空白核查和分派依据绕过客户端校验。
     */
    static String requiredText(String text, String message) {
        if (text == null || text.isBlank()) {
            // 必填正文在归一化前拒绝空值和纯空白。
            throw BizIllegalException.invalid(message);
        }
        return text.strip();
    }

    /**
     * 可空业务文本按实际输入保存。
     */
    static String optionalText(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    /**
     * 公共命令表仅保存规范 UUID。
     */
    static String commandId(String value) {
        try {
            // 按持久化所用的规范表示核对操作编号。
            if (value != null && UUID.fromString(value).toString().equals(value)) {
                // 规范编号可以直接用于命令查找。
                return value;
            }
        } catch (IllegalArgumentException invalid) {
            // 只返回校验错误，不保存非规范输入。
        }
        throw BizIllegalException.invalid("操作编号须为 UUID");
    }
}
