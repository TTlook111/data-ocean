package com.dataocean.module.datasource.entity.vo;

import lombok.Data;

/**
 * 数据源简要视图对象
 * <p>
 * 用于查询端展示用户可访问的数据源列表，仅包含必要的标识和描述信息。
 * </p>
 *
 * @author dataocean
 */
@Data
public class DatasourceSimpleVO {

    /** 数据源 ID */
    private Long id;
    /** 数据源名称 */
    private String name;
    /** 数据库名称 */
    private String databaseName;
    /** 数据源描述 */
    private String description;
}
