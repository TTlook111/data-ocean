package com.dataocean.module.datasource.entity.query;

import com.dataocean.common.PageQuery;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据源工作列表的必要搜索条件及公共分页输入。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SourceQuery extends PageQuery {

    /**
     * 数据源名称搜索词。
     */
    private String name;

    /**
     * 已支持的数据库类型。
     */
    private String databaseType;

    /**
     * 技术联系人姓名或登录名。
     */
    private String technicalContactKeyword;

    /**
     * 数据源配置生命周期。
     */
    private String lifecycle;
}
