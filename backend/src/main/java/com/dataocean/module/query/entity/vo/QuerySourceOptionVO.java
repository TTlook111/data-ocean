package com.dataocean.module.query.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 只使用权威来源标识和对当前用户可见的名称。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuerySourceOptionVO {

    /**
     * 可稳定定位当前请求或记录的编号。
     */
    private String id;

    /**
     * 调用者有权查看的来源名称。
     */
    private String name;
}
