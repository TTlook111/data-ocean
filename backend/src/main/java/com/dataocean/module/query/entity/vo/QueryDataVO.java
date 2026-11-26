package com.dataocean.module.query.entity.vo;

import java.time.Instant;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 限量数据表，数据更新时间无可信水位时为空。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryDataVO {

    /**
     * 与实际返回的只读查询结构相对应的列。
     */
    private List<QueryColumnVO> columns;

    /**
     * 受限的只读查询结果，数值保留字符串精度。
     */
    private List<List<String>> rows;

    /**
     * 当前返回是否受平台行数上限截断。
     */
    private boolean truncated;

    /**
     * 当前结果或计算口径所用的业务单位。
     */
    private String unit;

    /**
     * 按当前授权裁剪后的业务数据范围。
     */
    private String scope;

    /**
     * 来源可可信核对的数据更新时间。
     */
    private Instant dataUpdatedAt;

    /**
     * 实际统计的日期窗口。
     */
    private QuerySeriesWindowVO series;
}
