package com.dataocean.module.governance.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 治理问题批量处理结果。
 * <p>
 * 此前 `batchHandle` 只返回成功计数，状态不允许的条目被静默跳过（仅写服务端日志），
 * 前端只能显示「已处理 N 条」，无法告知用户有多少条被跳过、原因是什么，
 * 甚至会把「全部被跳过」渲染成绿色成功提示。
 * </p>
 *
 * @author DataOcean
 */
@Data
@Builder
public class IssueBatchHandleResultVO {

    /** 实际处理成功的条数 */
    private int updated;

    /** 被跳过的条数 */
    private int skipped;

    /** 被跳过的条目及原因；未跳过时为空列表 */
    private List<SkippedIssue> skippedIssues;

    /**
     * 单条被跳过的记录。
     */
    @Data
    @AllArgsConstructor
    public static class SkippedIssue {

        /** 问题 ID */
        private Long issueId;

        /** 跳过原因（来自业务异常信息） */
        private String reason;
    }
}
