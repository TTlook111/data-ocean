package com.dataocean.module.query.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 真实日序列覆盖的业务日期，缺少记录的日期保持空值。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuerySeriesWindowVO {

    /**
     * 开始的业务日期，使用 ISO 日期。
     */
    private String start;

    /**
     * 结束的业务日期，使用 ISO 日期。
     */
    private String end;
}
