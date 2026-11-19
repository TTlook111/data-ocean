package com.dataocean.module.access.entity.vo;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 个人或部门的一笔来源。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccessOriginVO {

    /**
     * 来源编号。
     */
    private String id;

    /**
     * 来源属于申请或部门。
     */
    private String kind;

    /**
     * 申请或部门编号。
     */
    private String originId;

    /**
     * 来源用于回看的名称。
     */
    private String originName;

    /**
     * 这笔来源适用的规则。
     */
    private List<OutputRuleVO> rules;
}
