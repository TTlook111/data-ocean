package com.dataocean.module.access.entity.vo;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前规则或办理时规则快照。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OutputRuleVO {

    /**
     * 规则稳定编号。
     */
    private String id;

    /**
     * 限制名称。
     */
    private String name;

    /**
     * 业务限制全文。
     */
    private String content;

    /**
     * 规则修订。
     */
    private int version;

    /**
     * 此版规则实际保存的 UTC 时刻。
     */
    private Instant updatedAt;
}
