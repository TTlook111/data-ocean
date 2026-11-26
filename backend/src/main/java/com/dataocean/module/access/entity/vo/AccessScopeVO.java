package com.dataocean.module.access.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 某数据源当前合并后的可用范围。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccessScopeVO {

    /**
     * 授权所指向的数据源。
     */
    private AccessSourceOptionVO source;

    /**
     * 当前全部有效来源的限制全文，按内容去重。
     */
    private List<OutputRuleVO> rules;

    /**
     * 各有效来源及其规则。
     */
    private List<AccessOriginVO> origins;
}
