package com.dataocean.module.access.entity.query;

import com.dataocean.common.PageQuery;

import java.time.LocalDate;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * 访问管理列表通用的分页及筛选字段。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
public class AccessListQuery extends PageQuery {

    /**
     * 列表主要名称或说明的搜索词。
     */
    private String keyword;

    /**
     * 独立查询共享规则的名称。
     */
    private String ruleName;

    /**
     * 独立查询返回限制全文。
     */
    private String ruleContent;

    /**
     * 限定到明确数据源。
     */
    private String sourceId;

    /**
     * 当前业务状态筛选。
     */
    private String state;

    /**
     * 审批队列分组。
     */
    private String queue;

    /**
     * 被授权人员姓名或登录名。
     */
    private String applicant;

    /**
     * 历史办理人员姓名或登录名。
     */
    private String handler;

    /**
     * 申请编号。
     */
    private String requestId;

    /**
     * 日期范围起点，含当天。
     */
    private LocalDate dateFrom;

    /**
     * 日期范围终点，含当天。
     */
    private LocalDate dateTo;

    /**
     * 日期筛选采用的 IANA 时区，缺省 UTC。
     */
    private String timeZone;
}
