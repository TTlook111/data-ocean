package com.dataocean.module.query.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 接受命令产生的稳定轮次身份。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryAcceptedVO {

    /**
     * 可稳定定位当前请求或记录的编号。
     */
    private String id;
}
