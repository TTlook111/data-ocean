package com.dataocean.module.datasource.entity.query;

import com.dataocean.common.PageQuery;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 按变化对象、类型、发现日期和处理阶段的交集查找来源变化。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StructureChangeQuery extends PageQuery {

    /**
     * 过滤限定名或属性。
     */
    private String keyword;

    /**
     * 结构变化类别。
     */
    private String changeType;

    /**
     * 发现起止日期中的第一日。
     */
    private String fromDate;

    /**
     * 发现起止日期中的最后一日。
     */
    private String toDate;

    /**
     * 日期筛选使用的业务时区。
     */
    private String timeZone = "Asia/Shanghai";

    /**
     * 当前处理阶段。
     */
    private String stage;

    /**
     * 由来源对象跳转时的限定标识。
     */
    private String objectId;

    /**
     * 由采集记录跳转时的任务标识。
     */
    private String taskId;
}
