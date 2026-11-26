package com.dataocean.module.query.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 等待时补充原问题缺少的业务条件。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClarifyQueryDTO {

    /**
     * 用户补足当前轮等待的业务条件。
     */
    private String answer;
}
