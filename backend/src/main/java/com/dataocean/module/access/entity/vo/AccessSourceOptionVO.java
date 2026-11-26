package com.dataocean.module.access.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 可配置源的最小安全引用。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccessSourceOptionVO {

    /**
     * 稳定源编号。
     */
    private String id;

    /**
     * 数据源名称。
     */
    private String name;
}
