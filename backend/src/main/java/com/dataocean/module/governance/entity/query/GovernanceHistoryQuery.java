package com.dataocean.module.governance.entity.query;

import com.dataocean.common.PageQuery;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 按原内容修订定位历史记录和确认。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class GovernanceHistoryQuery extends PageQuery {

    /**
     * 固定的历史修订号，空值列全部历史。
     */
    private Integer revision;
}
