package com.dataocean.module.query.entity.vo;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 近期同源轮次只返回基本定位。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryTurnVO {

    /**
     * 可稳定定位当前请求或记录的编号。
     */
    private String id;

    /**
     * 当前轮次使用的业务问题。
     */
    private String question;

    /**
     * 表示当前阶段的稳定状态编码。
     */
    private String state;

    /**
     * 实际接收或创建记录的 UTC 时刻。
     */
    private Instant createdAt;
}
