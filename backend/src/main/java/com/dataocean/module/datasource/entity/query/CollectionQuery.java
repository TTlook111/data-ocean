package com.dataocean.module.datasource.entity.query;

import com.dataocean.common.PageQuery;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 指定来源的采集记录查询，复用公共数据库分页参数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CollectionQuery extends PageQuery {

    /**
     * 采集任务编号的搜索词。
     */
    private String taskNoKeyword;

    /**
     * MANUAL、RETRY 或 SCHEDULED。
     */
    private String triggerKind;

    /**
     * 第一个实际开始日期，包含当天。
     */
    private String fromDate;

    /**
     * 最后一个实际开始日期，包含当天。
     */
    private String toDate;

    /**
     * 日期筛选按该业务时区转换为 UTC。
     */
    private String timeZone = "Asia/Shanghai";

    /**
     * 可筛选的任务五态。
     */
    private String state;

    /**
     * 回包不明时仅允许原主体按命令精确找回。
     */
    private String startCommandId;
}
