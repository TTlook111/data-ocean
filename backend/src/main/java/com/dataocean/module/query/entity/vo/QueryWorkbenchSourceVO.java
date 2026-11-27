package com.dataocean.module.query.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 问数工作台可回看的来源及当前能否提交新问题。
 */
@Data
@AllArgsConstructor
public class QueryWorkbenchSourceVO {

    /**
     * 访问历史与提交问数使用的数据源编号。
     */
    private String id;

    /**
     * 当前账号有权查看的来源名称。
     */
    private String name;

    /**
     * 允许提交时为零，否则使用已定义的问数阻断业务码。
     */
    private int askBlockedCode;
}
