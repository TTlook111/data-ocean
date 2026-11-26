package com.dataocean.common.overview;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 各看板核对自己的来源授权后，只向筛选栏提供名称与编号。
 */
@Data
@AllArgsConstructor
public class OverviewSourceVO {

    /**
     * 当前获准统计的来源编号。
     */
    private String id;

    /**
     * 来源当前展示名。
     */
    private String name;
}
