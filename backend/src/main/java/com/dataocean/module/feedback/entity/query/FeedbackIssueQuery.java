package com.dataocean.module.feedback.entity.query;

import com.dataocean.common.PageQuery;

import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 反馈事项队列的分组和筛选条件，分页沿用平台规则。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FeedbackIssueQuery extends PageQuery {

    /**
     * 限定事项所属的数据源。
     */
    private String sourceId;

    /**
     * 按事项编号精确定位。
     */
    private String issueId;

    /**
     * 队列分组，缺省显示全部有权查看的事项。
     */
    private String scope = "ALL";

    /**
     * 反馈评价或问数自动线索的来源编码。
     */
    private String origin;

    /**
     * 在事项编号及公开摘要中进行字面匹配。
     */
    @Size(max = 80)
    private String keyword;

    /**
     * 事项对外可见的阶段，含等待补充。
     */
    private String status;

    /**
     * 按主负责人的显示名称筛选。
     */
    @Size(max = 80)
    private String assignee;
}
