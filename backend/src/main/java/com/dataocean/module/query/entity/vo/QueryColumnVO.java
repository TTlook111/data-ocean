package com.dataocean.module.query.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 与实际执行 SQL 对应的结果列，数值仍保留数据库精度。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryColumnVO {

    /**
     * 调用者有权查看的来源名称。
     */
    private String name;

    /**
     * 当前结果可披露的列名或业务名称。
     */
    private String label;
}
